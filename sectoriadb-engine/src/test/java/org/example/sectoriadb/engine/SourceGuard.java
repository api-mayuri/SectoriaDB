package org.example.sectoriadb.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dependency guard without ArchUnit: reads the import lines of the main sources of a module and lets a test assert
 * which packages may import what (see docs/architecture/overview.md). Shared with the other modules through the
 * test-jar of sectoriadb-engine.
 */
public final class SourceGuard {

    public static final String BASE = "org.example.sectoriadb";

    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);

    /** One import of one source file. {@code pkg} is the package of the file, {@code imported} the full name that is imported. */
    public record Import(String file, String pkg, String imported) {

        /** The package of the imported type (the longest prefix before the first capitalised segment). */
        public String importedPackage() {
            StringBuilder sb = new StringBuilder();
            for (String part : imported.split("\\.")) {
                if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(part);
            }
            return sb.toString();
        }
    }

    private SourceGuard() {
    }

    /** All imports of all sources under {@code <moduleDir>/src/main/java}. */
    public static List<Import> mainImports(Path moduleDir) {
        Path root = moduleDir.resolve("src/main/java");
        List<Import> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                Matcher pm = PACKAGE.matcher(src);
                if (!pm.find()) continue;
                Matcher im = IMPORT.matcher(src);
                while (im.find()) out.add(new Import(root.relativize(f).toString(), pm.group(1), im.group(1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Imports whose file lies in package {@code pkg} or below it. */
    public static List<Import> inPackage(Collection<Import> imports, String pkg) {
        return imports.stream().filter(i -> i.pkg().equals(pkg) || i.pkg().startsWith(pkg + ".")).toList();
    }

    /** Imports (of any file) of a type from {@code prefix} or below it, excluding files that are themselves in {@code prefix}. */
    public static List<Import> importersOf(Collection<Import> imports, String prefix) {
        return imports.stream()
                .filter(i -> i.imported().equals(prefix) || i.imported().startsWith(prefix + "."))
                .filter(i -> !(i.pkg().equals(prefix) || i.pkg().startsWith(prefix + ".")))
                .toList();
    }

    /** Imports that start with one of the given prefixes. */
    public static List<Import> importing(Collection<Import> imports, String... prefixes) {
        List<Import> out = new ArrayList<>();
        for (Import i : imports) {
            for (String p : prefixes) {
                if (i.imported().equals(p) || i.imported().startsWith(p + ".")) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** Imports that are not from the JDK ({@code java.*}, {@code javax.*}) and not from one of the allowed prefixes. */
    public static List<Import> outsideOf(Collection<Import> imports, String... allowedPrefixes) {
        List<Import> out = new ArrayList<>();
        outer:
        for (Import i : imports) {
            if (i.imported().startsWith("java.") || i.imported().startsWith("javax.")) continue;
            for (String p : allowedPrefixes) {
                if (i.imported().equals(p) || i.imported().startsWith(p + ".")) continue outer;
            }
            out.add(i);
        }
        return out;
    }

    /** Package-level dependency graph restricted to packages that have sources in this module. */
    public static Map<String, Set<String>> packageGraph(Collection<Import> imports) {
        Set<String> own = new HashSet<>();
        for (Import i : imports) own.add(i.pkg());
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Import i : imports) {
            String to = i.importedPackage();
            graph.computeIfAbsent(i.pkg(), k -> new TreeSet<>());
            if (own.contains(to) && !to.equals(i.pkg())) graph.get(i.pkg()).add(to);
        }
        return graph;
    }

    /** Strongly connected components with more than one package (package cycles), ignoring the packages in {@code except}. */
    public static List<List<String>> cycles(Map<String, Set<String>> graph, Set<String> except) {
        Map<String, Set<String>> g = new TreeMap<>();
        graph.forEach((k, v) -> {
            if (except.contains(k)) return;
            Set<String> targets = new LinkedHashSet<>(v);
            targets.removeAll(except);
            g.put(k, targets);
        });
        List<List<String>> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] counter = {0};
        for (String v : g.keySet()) {
            if (!index.containsKey(v)) strong(v, g, index, low, onStack, stack, counter, result);
        }
        return result;
    }

    private static void strong(String v, Map<String, Set<String>> g, Map<String, Integer> index, Map<String, Integer> low,
                               Set<String> onStack, Deque<String> stack, int[] counter, List<List<String>> out) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : g.getOrDefault(v, Set.of())) {
            if (!g.containsKey(w)) continue;
            if (!index.containsKey(w)) {
                strong(w, g, index, low, onStack, stack, counter, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) {
                Collections.sort(component);
                out.add(component);
            }
        }
    }

    /** Readable form of violations for assertion messages. */
    public static String describe(Collection<Import> violations) {
        return violations.stream().map(i -> i.file() + " imports " + i.imported()).sorted().toList().toString();
    }
}
