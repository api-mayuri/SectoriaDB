package org.example.sectoriadb.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.example.sectoriadb.engine.SourceGuard.BASE;
import static org.example.sectoriadb.engine.SourceGuard.Import;
import static org.example.sectoriadb.engine.SourceGuard.cycles;
import static org.example.sectoriadb.engine.SourceGuard.describe;
import static org.example.sectoriadb.engine.SourceGuard.importing;
import static org.example.sectoriadb.engine.SourceGuard.inPackage;
import static org.example.sectoriadb.engine.SourceGuard.mainImports;
import static org.example.sectoriadb.engine.SourceGuard.outsideOf;
import static org.example.sectoriadb.engine.SourceGuard.packageGraph;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the engine module (docs/architecture/overview.md): JDK and slf4j only, no other SectoriaDB module,
 * and an acyclic package structure whose leaves ({@code error}, {@code hash}, {@code metrics}) depend on nothing.
 */
class ModuleDependencyGuardTest {

    private final List<Import> imports = mainImports(Path.of("."));

    @Test
    void onlyTheJdkSlf4jAndItselfAreImported() {
        List<Import> bad = outsideOf(imports, "org.slf4j", BASE + ".engine");
        assertTrue(bad.isEmpty(), "forbidden imports: " + describe(bad));
    }

    @Test
    void theEngineDoesNotKnowTheMetastoreNorTheApplication() {
        List<Import> bad = importing(imports, BASE + ".metastore", BASE + ".storage", BASE + ".catalog",
                BASE + ".maintenance", BASE + ".security", BASE + ".audit", BASE + ".config", BASE + ".metrics",
                BASE + ".s3", BASE + ".observability");
        assertTrue(bad.isEmpty(), "forbidden imports: " + describe(bad));
    }

    @Test
    void leafPackagesDependOnNothingInsideTheEngine() {
        for (String leaf : List.of("error", "hash", "metrics")) {
            List<Import> bad = inPackage(imports, BASE + ".engine." + leaf).stream()
                    .filter(i -> i.imported().startsWith(BASE + ".engine."))
                    .filter(i -> !i.importedPackage().equals(i.pkg()))
                    .toList();
            assertTrue(bad.isEmpty(), leaf + " must be a leaf: " + describe(bad));
        }
    }

    @Test
    void packagesHaveNoCycles() {
        assertEquals(List.of(), cycles(packageGraph(imports), Set.of()));
    }
}
