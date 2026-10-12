package org.example.sectoriadb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.example.sectoriadb.engine.SourceGuard.BASE;
import static org.example.sectoriadb.engine.SourceGuard.Import;
import static org.example.sectoriadb.engine.SourceGuard.cycles;
import static org.example.sectoriadb.engine.SourceGuard.describe;
import static org.example.sectoriadb.engine.SourceGuard.importersOf;
import static org.example.sectoriadb.engine.SourceGuard.importing;
import static org.example.sectoriadb.engine.SourceGuard.inPackage;
import static org.example.sectoriadb.engine.SourceGuard.mainImports;
import static org.example.sectoriadb.engine.SourceGuard.packageGraph;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the core module (docs/architecture/12-modularization.md): who may import the metastore, where Spring lives,
 * the direction of the layers and the absence of package cycles.
 */
class ModuleDependencyGuardTest {

    private static final String CATALOG = BASE + ".catalog";
    private static final String CATALOG_METASTORE = BASE + ".catalog.metastore";
    private static final String CONFIG = BASE + ".config";

    private final List<Import> imports = mainImports(Path.of("."));

    @Test
    void onlyCatalogMetastoreImportsTheMetastoreModule() {
        List<Import> bad = importing(imports, BASE + ".metastore").stream()
                .filter(i -> !i.pkg().equals(CATALOG_METASTORE)).toList();
        assertTrue(bad.isEmpty(), "only catalog.metastore may import the metastore module: " + describe(bad));
    }

    @Test
    void onlyTheCompositionRootImportsTheMetastoreBackedRepositories() {
        List<Import> bad = importersOf(imports, CATALOG_METASTORE).stream()
                .filter(i -> !i.pkg().equals(CONFIG)).toList();
        assertTrue(bad.isEmpty(), "only config wires catalog.metastore: " + describe(bad));
    }

    @Test
    void springIsConfinedToTheCompositionRoot() {
        List<Import> bad = importing(imports, "org.springframework").stream()
                .filter(i -> !i.pkg().equals(CONFIG)).toList();
        assertTrue(bad.isEmpty(), "lower layers are plain Java: " + describe(bad));
    }

    @Test
    void layersPointDownwards() {
        // catalog: entities and repository interfaces, below everything else of the core
        assertNoImports(CATALOG, true, ".storage", ".maintenance", ".security", ".audit", ".config", ".metrics");
        // audit and security know the catalog at most
        assertNoImports(BASE + ".audit", false, ".storage", ".maintenance", ".security", ".catalog.metastore");
        assertNoImports(BASE + ".security", false, ".storage", ".maintenance", ".audit", ".catalog.metastore");
        // storage does not know the maintenance jobs that use it
        assertNoImports(BASE + ".storage", false, ".maintenance", ".catalog.metastore");
        // the multipart use cases sit above the storage layer; the storage layer does not know them
        List<Import> upward = importing(imports.stream().filter(i -> i.pkg().equals(BASE + ".storage")).toList(),
                BASE + ".storage.multipart");
        assertTrue(upward.isEmpty(), "storage must not depend on storage.multipart: " + describe(upward));
        assertNoImports(BASE + ".storage.multipart", true, ".maintenance", ".catalog.metastore", ".config");
        // nothing of the application (server module) is visible from the core
        for (String pkg : List.of(CATALOG, BASE + ".storage", BASE + ".maintenance", BASE + ".audit", BASE + ".security")) {
            assertNoImports(pkg, false, ".s3", ".observability", ".shell", ".scheduler");
        }
    }

    private void assertNoImports(String pkg, boolean exactPackageOnly, String... forbiddenSuffixes) {
        List<Import> scope = exactPackageOnly
                ? imports.stream().filter(i -> i.pkg().equals(pkg)).toList()
                : inPackage(imports, pkg);
        String[] forbidden = new String[forbiddenSuffixes.length];
        for (int i = 0; i < forbidden.length; i++) forbidden[i] = BASE + forbiddenSuffixes[i];
        List<Import> bad = importing(scope, forbidden);
        assertTrue(bad.isEmpty(), pkg + " must not depend upwards: " + describe(bad));
    }

    @Test
    void packagesHaveNoCyclesBesidesTheCompositionRoot() {
        // config holds StorageProperties (read by everybody) and CoreConfiguration (wires everybody): that is the one cycle
        assertEquals(List.of(), cycles(packageGraph(imports), Set.of(CONFIG)));
    }
}
