package org.example.sectoriadb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.example.sectoriadb.engine.SourceGuard.BASE;
import static org.example.sectoriadb.engine.SourceGuard.Import;
import static org.example.sectoriadb.engine.SourceGuard.cycles;
import static org.example.sectoriadb.engine.SourceGuard.describe;
import static org.example.sectoriadb.engine.SourceGuard.importing;
import static org.example.sectoriadb.engine.SourceGuard.inPackage;
import static org.example.sectoriadb.engine.SourceGuard.mainImports;
import static org.example.sectoriadb.engine.SourceGuard.packageGraph;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the server module (docs/architecture/overview.md): the S3 packages form layers
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
            "multipart", Set.of("api", "access", "error", "http", "xml"));

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
                .map(p -> p.substring(S3.length() + 1)).collect(Collectors.toSet());
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
        // the server sees the catalog (CatalogInfo, CatalogUnavailableException), never the metastore module itself
        List<Import> bad = importing(imports, BASE + ".metastore");
        assertTrue(bad.isEmpty(), describe(bad));
        // and the metastore-backed repositories are wired by the core's composition root, not named by the server
        bad = importing(imports, BASE + ".catalog.metastore");
        assertTrue(bad.isEmpty(), describe(bad));
    }

    @Test
    void packagesHaveNoCycles() {
        assertEquals(List.of(), cycles(packageGraph(imports), Set.of()));
    }
}
