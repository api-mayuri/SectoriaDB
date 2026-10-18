package org.example.sectoriadb;

import org.example.sectoriadb.config.StorageProperties;
import org.example.sectoriadb.observability.ObservabilityProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documentation must not drift from the code: relative markdown links resolve, every {@code sectoriadb_*} metric
 * named in the docs is registered by the code, and docs/guide/configuration.md lists exactly the {@code sectoriadb.*}
 * properties the code binds.
 */
class DocsConsistencyTest {

    private static final Path ROOT = findRoot();

    private static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !(Files.exists(p.resolve("docs/README.md")) && Files.exists(p.resolve("pom.xml")))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("repository root (docs/README.md + pom.xml) not found above " + Path.of("").toAbsolutePath());
        return p;
    }

    private static List<Path> markdownFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(ROOT.resolve("README.md"));
        files.add(ROOT.resolve("CHANGELOG.md"));
        files.add(ROOT.resolve("bench/README.md"));
        files.add(ROOT.resolve("bench/results/README.md"));
        try (Stream<Path> s = Files.walk(ROOT.resolve("docs"))) {
            s.filter(f -> f.toString().endsWith(".md")).forEach(files::add);
        }
        return files;
    }

    private static String stripCode(String md) {
        return md.replaceAll("(?s)```.*?```", "").replaceAll("`[^`\n]*`", "");
    }

    @Test
    void relativeMarkdownLinksResolve() throws IOException {
        Pattern link = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
        List<String> broken = new ArrayList<>();
        for (Path file : markdownFiles()) {
            Matcher m = link.matcher(stripCode(Files.readString(file, StandardCharsets.UTF_8)));
            while (m.find()) {
                String target = m.group(1);
                if (target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || target.startsWith("#")) continue;
                int hash = target.indexOf('#');
                if (hash >= 0) target = target.substring(0, hash);
                Path resolved = file.getParent().resolve(target).normalize();
                if (!Files.exists(resolved)) broken.add(ROOT.relativize(file) + " -> " + m.group(1));
            }
        }
        assertTrue(broken.isEmpty(), "broken links: " + broken);
    }

    private static String normalizeMetric(String name) {
        String n = name;
        String prev;
        do {
            prev = n;
            for (String suffix : new String[]{"_bucket", "_count", "_sum", "_max", "_total", "_seconds", "_bytes"}) {
                if (n.endsWith(suffix)) n = n.substring(0, n.length() - suffix.length());
            }
        } while (!n.equals(prev));
        return n;
    }

    private static List<Path> mainSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String module : new String[]{"sectoriadb-metastore", "sectoriadb-engine", "sectoriadb-core", "sectoriadb-server"}) {
            try (Stream<Path> s = Files.walk(ROOT.resolve(module).resolve("src/main/java"))) {
                s.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void metricsNamedInTheDocsAreRegisteredByTheCode() throws IOException {
        Set<String> registered = new TreeSet<>();
        Pattern literal = Pattern.compile("\"(sectoriadb\\.[a-z0-9.]+)\"");
        for (Path src : mainSources()) {
            Matcher m = literal.matcher(Files.readString(src, StandardCharsets.UTF_8));
            while (m.find()) registered.add(normalizeMetric(m.group(1).replace('.', '_')));
        }
        assertTrue(registered.contains("sectoriadb_s3_requests"), "the metric scan found nothing: " + registered);

        // not followed by '_' or '*' (wildcards like sectoriadb_gc_* name a family, not a metric)
        Pattern metric = Pattern.compile("sectoriadb_[a-z0-9_]*[a-z0-9](?![a-z0-9_*])");
        List<String> unknown = new ArrayList<>();
        int checked = 0;
        for (Path file : markdownFiles()) {
            Matcher m = metric.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                checked++;
                if (!registered.contains(normalizeMetric(m.group()))) unknown.add(ROOT.relativize(file) + ": " + m.group());
            }
        }
        assertTrue(checked > 50, "too few metric mentions found: " + checked);
        assertTrue(unknown.isEmpty(), "metrics in the docs that the code does not register: " + unknown);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static void collect(Class<?> type, String prefix, Set<String> out) {
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String name = prefix + "." + kebab(f.getName());
            if (f.getType().getEnclosingClass() == StorageProperties.class) {
                collect(f.getType(), name, out);
            } else {
                out.add(name);
            }
        }
    }

    @Test
    void configurationGuideListsExactlyThePropertiesTheCodeBinds() throws IOException {
        Set<String> inCode = new TreeSet<>();
        collect(StorageProperties.class, "sectoriadb", inCode);
        collect(ObservabilityProperties.class, "sectoriadb.observability", inCode);
        Pattern placeholder = Pattern.compile("\\$\\{(sectoriadb\\.[a-z0-9.-]+)");
        for (Path src : mainSources()) {
            Matcher m = placeholder.matcher(Files.readString(src, StandardCharsets.UTF_8));
            while (m.find()) inCode.add(m.group(1));
        }
        assertTrue(inCode.contains("sectoriadb.s3.limits.max-inflight-writes-per-key"), "scan of @Value properties failed: " + inCode);
        assertTrue(inCode.contains("sectoriadb.gc.small-compact-dead-percent"), "reflection over StorageProperties failed: " + inCode);

        Set<String> inDocs = new TreeSet<>();
        Pattern row = Pattern.compile("^\\| `(sectoriadb\\.[a-z0-9.-]+)` \\|", Pattern.MULTILINE);
        Matcher m = row.matcher(Files.readString(ROOT.resolve("docs/guide/configuration.md"), StandardCharsets.UTF_8));
        while (m.find()) inDocs.add(m.group(1));
        assertFalse(inDocs.isEmpty());

        Set<String> missingInDocs = new TreeSet<>(inCode);
        missingInDocs.removeAll(inDocs);
        Set<String> unknownInDocs = new TreeSet<>(inDocs);
        unknownInDocs.removeAll(inCode);
        assertEquals(Set.of(), missingInDocs, "properties bound in code but absent from configuration.md");
        assertEquals(Set.of(), unknownInDocs, "properties in configuration.md that the code does not bind");
    }
}
