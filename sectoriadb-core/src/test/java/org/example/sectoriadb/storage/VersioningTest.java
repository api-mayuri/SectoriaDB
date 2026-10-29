package org.example.sectoriadb.storage;

import org.example.sectoriadb.catalog.LifecycleRule;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository.DeleteOutcome;
import org.example.sectoriadb.catalog.ManifestRepository.VersionEntry;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.metastore.MetaInvariants;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bucket versioning in the catalog (docs/architecture/versioning.md). */
class VersioningTest {

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        pool = rig.bucket("vb");
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    ManifestEntity put(String key, String body, int size) throws Exception {
        byte[] b = size > 0 ? new byte[size] : body.getBytes(StandardCharsets.UTF_8);
        if (size > 0) System.arraycopy(body.getBytes(StandardCharsets.UTF_8), 0, b, 0, Math.min(body.length(), size));
        return rig.writer.storeStream(new ByteArrayInputStream(b), pool, key, "text/plain");
    }

    String read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.reader.streamToOutput(m, out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\0", "");
    }

    List<String> versionIds(String key) {
        return rig.manifests.listVersions("vb", key, null, null, null, 1000).versions().stream()
                .filter(e -> e.key().equals(key)).map(VersionEntry::versionId).toList();
    }

    @Test
    void enabledKeepsEveryVersionAndADeleteOnlyAddsAMarker() throws Exception {
        put("k", "before", 0);                                    // written unversioned: the null version
        rig.poolService.setVersioning("vb", "Enabled");
        ManifestEntity v1 = put("k", "one", 0);
        ManifestEntity v2 = put("k", "two-chunked", 40_000);       // a chunked version too
        assertEquals(v1.getId(), v1.getVersionId());
        assertEquals(List.of(v2.getId(), v1.getId(), "null"), versionIds("k"));
        assertEquals("two-chunked", read(rig.catalog.findObject("vb", "k").orElseThrow()));
        assertEquals("one", read(rig.manifests.findVersion("vb", "k", v1.getId()).manifest().orElseThrow()));
        assertEquals("before", read(rig.manifests.findVersion("vb", "k", "null").manifest().orElseThrow()));

        DeleteOutcome d = rig.manifests.delete("vb", "k", null);
        assertTrue(d.deleteMarker());
        assertTrue(d.removed().isEmpty(), "nothing is removed for good");
        assertTrue(rig.catalog.findObject("vb", "k").isEmpty(), "the key looks deleted");
        assertEquals(d.versionId(), rig.manifests.latestDeleteMarker("vb", "k").orElseThrow());
        assertTrue(rig.manifests.findVersion("vb", "k", d.versionId()).marker());

        // the collector does not touch versions that are only noncurrent
        rig.gc.run(Options.defaults().withGrace(0));
        assertEquals("two-chunked", read(rig.manifests.findVersion("vb", "k", v2.getId()).manifest().orElseThrow()));

        // removing the marker brings the newest version back
        rig.manifests.delete("vb", "k", d.versionId());
        assertEquals(v2.getId(), rig.catalog.findObject("vb", "k").orElseThrow().getId());
    }

    @Test
    void deletingAVersionForGoodPromotesTheNextOneAndFreesItsData() throws Exception {
        rig.poolService.setVersioning("vb", "Enabled");
        ManifestEntity v1 = put("k", "one", 0);
        ManifestEntity v2 = put("k", "two", 0);
        DeleteOutcome d = rig.manifests.delete("vb", "k", v2.getId());
        assertEquals(v2.getId(), d.removed().orElseThrow().getId());
        assertFalse(d.deleteMarker());
        assertEquals(v1.getId(), rig.catalog.findObject("vb", "k").orElseThrow().getId(), "the previous version is current again");
        rig.manifests.delete("vb", "k", v1.getId());
        assertTrue(rig.catalog.findObject("vb", "k").isEmpty());
        assertEquals(List.of(), versionIds("k"));
        assertFalse(rig.manifests.hasVersions("vb"));
        assertEquals(2L, rig.manifests.gcQueueSize(), "both versions went to the collector");
        assertFalse(rig.manifests.delete("vb", "k", "no-such-version").removed().isPresent(), "a missing version is a no-op");
    }

    @Test
    void suspendedWritesTheNullVersionAndReplacesIt() throws Exception {
        rig.poolService.setVersioning("vb", "Enabled");
        ManifestEntity v1 = put("k", "one", 0);
        rig.poolService.setVersioning("vb", "Suspended");
        ManifestEntity n1 = put("k", "null-1", 0);
        assertNull(n1.getVersionId());
        ManifestEntity n2 = put("k", "null-2", 0);
        assertEquals(List.of("null", v1.getId()), versionIds("k"), "the second null version replaced the first");
        assertEquals("null-2", read(rig.manifests.findVersion("vb", "k", "null").manifest().orElseThrow()));
        assertTrue(rig.manifests.findById(n1.getId()).isEmpty(), "the replaced null version is gone");

        DeleteOutcome d = rig.manifests.delete("vb", "k", null);
        assertEquals("null", d.versionId());
        assertTrue(d.deleteMarker());
        assertEquals(n2.getId(), d.removed().orElseThrow().getId(), "the null version is replaced by the null marker");
        assertEquals(List.of("null", v1.getId()), versionIds("k"));
        assertTrue(rig.manifests.findVersion("vb", "k", "null").marker());
    }

    @Test
    void theListingGivesKeysInOrderVersionsNewestFirstAndPagesWithMarkers() throws Exception {
        rig.poolService.setVersioning("vb", "Enabled");
        put("a", "a1", 0);
        put("a", "a2", 0);
        put("b/x", "bx", 0);
        rig.manifests.delete("vb", "c", null);                      // a marker for a key that never had a version
        var all = rig.manifests.listVersions("vb", "", null, null, null, 1000).versions();
        assertEquals(List.of("a", "a", "b/x", "c"), all.stream().map(VersionEntry::key).toList());
        assertTrue(all.get(0).latest() && !all.get(1).latest());
        assertTrue(all.get(3).deleteMarker() && all.get(3).latest());

        var page1 = rig.manifests.listVersions("vb", "", null, null, null, 1);
        assertTrue(page1.truncated());
        var page2 = rig.manifests.listVersions("vb", "", null, page1.nextKeyMarker(), page1.nextVersionIdMarker(), 1);
        assertEquals(all.get(1).versionId(), page2.versions().get(0).versionId());

        var grouped = rig.manifests.listVersions("vb", "", "/", null, null, 1000);
        assertEquals(List.of("b/"), grouped.commonPrefixes());
        assertEquals(3, grouped.versions().size());
    }

    @Test
    void lifecycleRemovesOldNoncurrentVersionsAndLoneDeleteMarkers() throws Exception {
        rig.poolService.setVersioning("vb", "Enabled");
        put("logs/x", "1", 0);
        put("logs/x", "2", 0);
        ManifestEntity keep = put("logs/x", "3", 0);
        rig.manifests.delete("vb", "logs/gone", null);              // a marker without versions
        put("other", "1", 0);
        put("other", "2", 0);
        var rules = List.of(new LifecycleRule("r1", true, "logs/", 1, true));
        // ExpiredObjectDeleteMarker has no age (as in S3): the marker without versions goes at once; the versions wait a day
        assertEquals(1, rig.manifests.applyLifecycle("vb", rules, System.currentTimeMillis(), 100), "only the lone marker");
        long dayLater = System.currentTimeMillis() + 86_400_000L + 1000;
        assertEquals(2, rig.manifests.applyLifecycle("vb", rules, dayLater, 100), "the two noncurrent versions");
        assertEquals(List.of(keep.getId()), versionIds("logs/x"));
        assertEquals(List.of(), versionIds("logs/gone"));
        assertEquals(2, versionIds("other").size(), "outside the rule's prefix");
    }

    @Test
    void aBucketWithVersionsIsNotEmpty() throws Exception {
        rig.poolService.setVersioning("vb", "Enabled");
        ManifestEntity v = put("k", "one", 0);
        DeleteOutcome marker = rig.manifests.delete("vb", "k", null);
        assertThrows(IllegalStateException.class, () -> rig.poolService.deleteBucket("vb"));
        rig.manifests.delete("vb", "k", v.getId());
        assertThrows(IllegalStateException.class, () -> rig.poolService.deleteBucket("vb"), "the marker is left");
        rig.manifests.delete("vb", "k", marker.versionId());
        rig.poolService.deleteBucket("vb");
        assertNotNull(marker.versionId());
    }

    @Test
    void versioningCannotBeSwitchedOff() {
        assertThrows(IllegalArgumentException.class, () -> rig.poolService.setVersioning("vb", "Disabled"));
    }
}
