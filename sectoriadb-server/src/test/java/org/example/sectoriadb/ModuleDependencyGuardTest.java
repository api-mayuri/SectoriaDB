package org.example.sectoriadb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.example.sectoriadb.engine.SourceGuard.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the server module (docs/architecture/12-modularization.md): the S3 packages form layers
 * (xml, error, http, access, auth, api, multipart), observability knows nothing of S3, and there are no package cycles.
 */
class ModuleDependencyGuardTest {

    private static final String S3 = BASE + ".s3";

    /** Package below s3 to the s3 packages it may import. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "xml", Set.of(),
            "error", Set.of("xml"),
            "http", Set.of("error"),
            "access", Set.of("error", "http", "xml"),
            "auth", Set.of("access", "error", "http", "xml"),
            "api", Set.of("access", "auth", "error", "http", "xml"),
            "multipart", Set.of("api", "error", "http", "xml"));

    private final List<Import> imports = mainImports(Path.of("."));

    @Test
    void s3PackagesImportOnlyTheLayersBelowThem() {
        for (Map.Entry<String, Set<String>> rule : ALLOWED.entrySet()) {
            String pkg = S3 + "." + rule.getKey();
            List<Import> bad = imports.stream()
                    .filter(i -> i.pkg().equals(pkg))
                    .filter(i -> i.importedPackage().startsWith(S3 + "."))
                    .filter(i -> !i.importedPackage().equals(pkg))
                    .filter(i -> !rule.getValue().contains(i.importedPackage().substring(S3.length() + 1)))
                    .toList();
            assertTrue(bad.isEmpty(), pkg + " imports a package it must not depend on: " + describe(bad));
        }
        assertEquals(ALLOWED.keySet(), s3Packages(), "a new s3 package needs a rule in this test");
    }

    private Set<String> s3Packages() {
        return imports.stream().map(Import::pkg).filter(p -> p.startsWith(S3 + "."))
                .map(p -> p.substring(S3.length() + 1)).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void observabilityShellAndSchedulersDoNotDependOnS3() {
        for (String pkg : List.of("observability", "shell", "scheduler")) {
            List<Import> bad = importing(inPackage(imports, BASE + "." + pkg), S3);
            assertTrue(bad.isEmpty(), pkg + " must not import s3: " + describe(bad));
        }
    }

    @Test
    void theMetastoreModuleIsReachedOnlyThroughTheCore() {
        // two read-only touch points are left: the metastore gauges and the mapping of its unavailable exception to 503 / 507
        Set<String> allowedFiles = Set.of("org/example/sectoriadb/observability/StorageGauges.java",
                "org/example/sectoriadb/s3/error/S3ExceptionHandler.java");
        List<Import> bad = importing(imports, BASE + ".metastore").stream()
                .filter(i -> !allowedFiles.contains(i.file())).toList();
        assertTrue(bad.isEmpty(), describe(bad));
    }

    @Test
    void packagesHaveNoCycles() {
        assertEquals(List.of(), cycles(packageGraph(imports), Set.of()));
    }
}
