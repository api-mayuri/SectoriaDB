package org.example.sectoriadb.storage.multipart;

import org.example.sectoriadb.catalog.BlobFileEntity;
import org.example.sectoriadb.catalog.BlobKind;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.MultipartRepository.CompleteCommand;
import org.example.sectoriadb.catalog.MultipartRepository.RequestedPart;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.catalog.metastore.MetaInvariants;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multipart objects and open uploads next to the maintenance jobs: resize repoints chunk locations through the chunk index,
 * small-blob compaction repoints the small segments through {@code manifests_by_blob}, copies read a multipart source, and
 * the listing of uploads follows S3's rules.
 */
class MultipartMaintenanceTest {

    static final int CHUNK = StorageRig.CHUNK;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
        rig.props.getSmallObject().setMaxFileBytes(20_000);
        rig.props.getGc().setSmallCompactMinDeadBytes(0);
        rig.props.setDefaultNumBuckets(64);
        pool = rig.bucket("bkt");
    }

    @AfterEach
    void tearDown() {
        try {
            MetaInvariants.check(rig.store);
        } finally {
            rig.close();
        }
    }

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    MultipartUpload create(String key) {
        MultipartUpload t = new MultipartUpload();
        t.setKey(key);
        return rig.multipart.create(pool, t);
    }

    MultipartPart upload(MultipartUpload u, int n, byte[] data) throws Exception {
        return rig.multipart.uploadPart(rig.multipart.requireOpen(u.getUploadId(), u.getBucket(), u.getKey()), n,
                new ByteArrayInputStream(data), UploadChecksums.none());
    }

    void complete(MultipartUpload u, MultipartPart... parts) {
        List<RequestedPart> req = new ArrayList<>();
        for (MultipartPart p : parts) req.add(new RequestedPart(p.partNumber(), p.etag(), Map.of()));
        rig.multipart.complete(new CompleteCommand(u.getUploadId(), u.getBucket(), u.getKey(), req,
                ManifestRepository.CommitPrecondition.NONE, Map.of(), System.currentTimeMillis()));
    }

    byte[] read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.reader.streamToOutput(m, out);
        return out.toByteArray();
    }

    byte[] get(String key) throws Exception {
        return read(rig.catalog.findObject("bkt", key).orElseThrow());
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ resize

    @Test
    void resizeKeepsMultipartObjectsAndOpenUploadsReadable() throws Exception {
        byte[] a1 = bytes(6 * CHUNK + 11, 1), a2 = bytes(4 * CHUNK, 2), a3 = bytes(300, 3);
        MultipartUpload done = create("done");
        complete(done, upload(done, 1, a1), upload(done, 2, a2), upload(done, 3, a3));

        MultipartUpload open = create("open");
        byte[] b1 = bytes(5 * CHUNK + 1, 4);
        MultipartPart pb1 = upload(open, 1, b1);

        for (BlobFileEntity b : rig.blobService.cuckooBlobsOf(pool)) {
            rig.resizeService.resizeBlobFile(b.getId(), 128);
        }
        assertArrayEquals(concat(a1, a2, a3), get("done"));
        assertTrue(rig.verifier.verify(rig.catalog.findObject("bkt", "done").orElseThrow()).ok());

        // the open upload continues across the resize: a new part and the part uploaded before it
        byte[] b2 = bytes(2 * CHUNK + 5, 5);
        MultipartPart pb2 = upload(open, 2, b2);
        complete(open, pb1, pb2);
        assertArrayEquals(concat(b1, b2), get("open"));
        rig.reopen();
        pool = rig.pools.findByName("bkt").orElseThrow();
        assertArrayEquals(concat(b1, b2), get("open"));
        assertArrayEquals(concat(a1, a2, a3), get("done"));
    }

    // ------------------------------------------------------------------ small blob compaction

    @Test
    void smallBlobCompactionRepointsSmallSegmentsOfMultipartObjectsAndOpenParts() throws Exception {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        List<String> doomed = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            MultipartUpload u = create("mp" + i);
            byte[] p1 = bytes(900, 100 + i), p2 = bytes(1000, 200 + i), p3 = bytes(2 * CHUNK + 3, 300 + i);
            complete(u, upload(u, 1, p1), upload(u, 2, p2), upload(u, 3, p3));
            byte[] whole = concat(p1, p2, p3);
            if (i % 3 == 0) {
                expected.put("mp" + i, whole);
            } else {
                doomed.add("mp" + i);
            }
            // and one plain small object per round, so the blobs also hold neighbours
            byte[] plain = bytes(800, 400 + i);
            rig.writer.storeStream(new ByteArrayInputStream(plain), pool, "plain" + i, "application/octet-stream");
            expected.put("plain" + i, plain);
        }
        MultipartUpload open = create("open");
        byte[] o1 = bytes(950, 500), o2 = bytes(40, 501);
        MultipartPart po1 = upload(open, 1, o1);
        MultipartPart po2 = upload(open, 2, o2);
        assertTrue(smallBlobs().size() >= 2, "the records span several small blobs: " + smallBlobs().size());

        for (String k : doomed) rig.catalog.deleteObject("bkt", k);
        rig.gc.run(Options.defaults().withGrace(0));          // flips the retired small segments to DELETED
        var reports = rig.gc.compact(pool, true);
        assertTrue(reports.stream().anyMatch(r -> r.compacted()), reports.toString());

        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), e.getKey());
        for (String k : expected.keySet()) {
            ManifestEntity m = rig.catalog.findObject("bkt", k).orElseThrow();
            assertTrue(rig.verifier.verify(m).ok(), k + " " + rig.verifier.verify(m).details());
        }
        // the part uploaded before the compaction is still found by Complete
        complete(open, po1, po2);
        assertArrayEquals(concat(o1, o2), get("open"));
        rig.gc.run(Options.defaults().withGrace(0));
        rig.reopen();
        pool = rig.pools.findByName("bkt").orElseThrow();
        for (var e : expected.entrySet()) assertArrayEquals(e.getValue(), get(e.getKey()), "after restart " + e.getKey());
        assertArrayEquals(concat(o1, o2), get("open"));
    }

    List<BlobFileEntity> smallBlobs() {
        return rig.blobs.findByPoolId(pool.getId()).stream().filter(b -> b.getKind() == BlobKind.SMALL).toList();
    }

    // ------------------------------------------------------------------ copies from a multipart source

    @Test
    void uploadPartCopyReadsAMultipartSourceWholeAndByRange() throws Exception {
        byte[] p1 = bytes(3 * CHUNK + 17, 11), p2 = bytes(700, 12), p3 = bytes(CHUNK, 13);
        MultipartUpload src = create("src");
        complete(src, upload(src, 1, p1), upload(src, 2, p2), upload(src, 3, p3));
        byte[] whole = concat(p1, p2, p3);
        ManifestEntity source = rig.catalog.findObject("bkt", "src").orElseThrow();
        assertEquals(StorageKind.MULTIPART, source.getStorageKind());

        MultipartUpload dst = create("dst");
        var open = rig.multipart.requireOpen(dst.getUploadId(), "bkt", "dst");
        MultipartPart c1 = rig.multipart.uploadPartCopy(open, 1, source, null);
        long from = 2L * CHUNK + 5, len = p1.length + 100 - from;                 // crosses the boundary of parts 1 and 2
        MultipartPart c2 = rig.multipart.uploadPartCopy(open, 2, source, new long[]{from, len});
        complete(dst, c1, c2);
        byte[] expected = concat(whole, java.util.Arrays.copyOfRange(whole, (int) from, (int) (from + len)));
        assertArrayEquals(expected, get("dst"));
        assertEquals(whole.length, c1.size());

        // a plain CopyObject-style read of the source is the same stream
        assertArrayEquals(whole, read(source));
    }

    // ------------------------------------------------------------------ ListMultipartUploads

    @Test
    void listingFollowsPrefixDelimiterAndMarkers() throws Exception {
        List<String> keys = List.of("a/1", "a/2", "a/3", "b", "c/x/1", "c/y", "d");
        Map<String, List<MultipartUpload>> byKey = new LinkedHashMap<>();
        for (String k : keys) {
            List<MultipartUpload> l = new ArrayList<>();
            l.add(create(k));
            if (k.equals("b")) {
                Thread.sleep(3);
                l.add(create(k));       // two uploads of one key, ordered by initiation
                Thread.sleep(3);
                l.add(create(k));
            }
            byKey.put(k, l);
        }
        MultipartUpload finished = create("e");
        complete(finished, upload(finished, 1, bytes(5, 1)));
        MultipartUpload aborted = create("f");
        rig.multipart.abort(aborted.getUploadId(), "bkt", "f");

        var all = rig.multipart.listUploads("bkt", "", null, "", "", 100);
        assertEquals(List.of("a/1", "a/2", "a/3", "b", "b", "b", "c/x/1", "c/y", "d"),
                all.uploads().stream().map(MultipartUpload::getKey).toList(), "completed and aborted uploads are not listed");
        assertEquals(byKey.get("b").stream().map(MultipartUpload::getUploadId).toList(),
                all.uploads().stream().filter(u -> u.getKey().equals("b")).map(MultipartUpload::getUploadId).toList(),
                "uploads of one key are ordered by initiation time");
        assertFalse(all.truncated());

        var prefixed = rig.multipart.listUploads("bkt", "a/", null, "", "", 100);
        assertEquals(List.of("a/1", "a/2", "a/3"), prefixed.uploads().stream().map(MultipartUpload::getKey).toList());

        var grouped = rig.multipart.listUploads("bkt", "", "/", "", "", 100);
        assertEquals(List.of("b", "b", "b", "d"), grouped.uploads().stream().map(MultipartUpload::getKey).toList());
        assertEquals(List.of("a/", "c/"), grouped.commonPrefixes());

        // paging through everything, two entries at a time, with and without a delimiter
        for (String delimiter : new String[]{null, "/"}) {
            List<String> seen = new ArrayList<>();
            String keyMarker = "", idMarker = "";
            int pages = 0;
            while (true) {
                var page = rig.multipart.listUploads("bkt", "", delimiter, keyMarker, idMarker, 2);
                page.commonPrefixes().forEach(p -> seen.add("P:" + p));
                page.uploads().forEach(u -> seen.add(u.getKey() + "#" + u.getUploadId()));
                pages++;
                if (!page.truncated()) break;
                keyMarker = page.nextKeyMarker();
                idMarker = page.nextUploadIdMarker() == null ? "" : page.nextUploadIdMarker();
                assertTrue(pages < 20, "paging must terminate");
            }
            var unpaged = rig.multipart.listUploads("bkt", "", delimiter, "", "", 100);
            List<String> expected = new ArrayList<>();
            // same content as one request; order within a page is uploads first, then prefixes, so compare as sets
            unpaged.commonPrefixes().forEach(p -> expected.add("P:" + p));
            unpaged.uploads().forEach(u -> expected.add(u.getKey() + "#" + u.getUploadId()));
            assertEquals(new java.util.TreeSet<>(expected), new java.util.TreeSet<>(seen), "delimiter " + delimiter);
            assertEquals(expected.size(), seen.size(), "nothing is listed twice");
        }

        // upload-id-marker: continue right after the first upload of "b"
        var afterFirstB = rig.multipart.listUploads("bkt", "", null, "b", byKey.get("b").get(0).getUploadId(), 100);
        assertEquals(List.of("b", "b", "c/x/1", "c/y", "d"), afterFirstB.uploads().stream().map(MultipartUpload::getKey).toList());
        var afterB = rig.multipart.listUploads("bkt", "", null, "b", "", 100);
        assertEquals(List.of("c/x/1", "c/y", "d"), afterB.uploads().stream().map(MultipartUpload::getKey).toList());
        assertTrue(rig.multipartRepo.hasOpenUploads("bkt"));
        assertEquals(List.of(), rig.multipart.listUploads("bkt", "", null, "", "", 0).uploads());
    }

    @Test
    void listPartsPaginates() throws Exception {
        MultipartUpload u = create("pages");
        for (int n : new int[]{1, 2, 3, 5, 8, 13}) upload(u, n, bytes(10 * n, n));
        var p1 = rig.multipart.listParts(u.getUploadId(), "bkt", "pages", 0, 4);
        assertEquals(List.of(1, 2, 3, 5), p1.parts().stream().map(MultipartPart::partNumber).toList());
        assertTrue(p1.truncated());
        assertEquals(5, p1.nextPartNumberMarker());
        var p2 = rig.multipart.listParts(u.getUploadId(), "bkt", "pages", p1.nextPartNumberMarker(), 4);
        assertEquals(List.of(8, 13), p2.parts().stream().map(MultipartPart::partNumber).toList());
        assertFalse(p2.truncated());
        assertEquals(0, p2.nextPartNumberMarker());
        assertEquals(List.of(8), rig.multipart.listParts(u.getUploadId(), "bkt", "pages", 5, 1).parts().stream()
                .map(MultipartPart::partNumber).toList());
    }
}
