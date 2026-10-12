package org.example.sectoriadb.metastore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metastore module depends on the JDK and slf4j only: no Spring, no Jackson, no other SectoriaDB module.
 * (docs/architecture/12-modularization.md)
 */
class ModuleDependencyGuardTest {

    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);

    @Test
    void onlyTheJdkAndSlf4jAreImported() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = IMPORT.matcher(Files.readString(f));
                while (m.find()) {
                    String imp = m.group(1);
                    boolean ok = imp.startsWith("java.") || imp.startsWith("javax.") || imp.startsWith("org.slf4j.")
                            || imp.startsWith("org.example.sectoriadb.metastore.");
                    if (!ok) violations.add(f.getFileName() + " imports " + imp);
                }
            }
        }
        assertTrue(violations.isEmpty(), "forbidden imports: " + violations);
    }
}
