package org.example.sectoriadb.storage.multipart;

import org.example.sectoriadb.catalog.BlobKind;
import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.MultipartException;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.MultipartRepository.CompleteCommand;
import org.example.sectoriadb.catalog.MultipartRepository.RequestedPart;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.catalog.metastore.MetaInvariants;
import org.example.sectoriadb.catalog.metastore.MetaStoreMultipartRepository;
import org.example.sectoriadb.catalog.metastore.MetaStoreProvider;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.UploadChecksums;
import org.example.sectoriadb.engine.metrics.EngineListener.Target;
import org.example.sectoriadb.maintenance.gc.GcReports.Options;
import org.example.sectoriadb.storage.StorageRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multipart state in the metastore (doc 13): parts as hidden manifests, zero-copy Complete, checksums derived from the
 * stored part values, abort and expiry. The invariant checker runs after every test.
 */
class MultipartStorageTest {

    static final int CHUNK = StorageRig.CHUNK;

    @TempDir
    Path root;

    StorageRig rig;
    PoolEntity pool;

    @BeforeEach
    void setUp() {
        rig = new StorageRig(root);
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

    // ------------------------------------------------------------------ helpers

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        parts.forEach(o::writeBytes);
        return o.toByteArray();
    }

    MultipartUpload create(String key) {
        return create(key, null, null);
    }

    MultipartUpload create(String key, ChecksumAlgorithm alg, ChecksumType type) {
        MultipartUpload t = new MultipartUpload();
        t.setKey(key);
        t.setContentType("application/x-test");
        t.setChecksumAlgorithm(alg);
        t.setChecksumType(type);
        return rig.multipart.create(pool, t);
    }

    MultipartPart upload(MultipartUpload u, int n, byte[] data) throws Exception {
        UploadChecksums wanted = UploadChecksums.none();
        if (u.getChecksumAlgorithm() != null) wanted.algorithm(u.getChecksumAlgorithm(), null);
        return rig.multipart.uploadPart(rig.multipart.requireOpen(u.getUploadId(), u.getBucket(), u.getKey()), n,
                new ByteArrayInputStream(data), wanted);
    }

    MultipartRepository.CompleteResult complete(MultipartUpload u, MultipartPart... parts) {
        return complete(u, ManifestRepository.CommitPrecondition.NONE, Map.of(), parts);
    }

    MultipartRepository.CompleteResult complete(MultipartUpload u, ManifestRepository.CommitPrecondition pre,
                                                Map<ChecksumAlgorithm, String> whole, MultipartPart... parts) {
        List<RequestedPart> req = new ArrayList<>();
        for (MultipartPart p : parts) req.add(new RequestedPart(p.partNumber(), "\"" + p.etag() + "\"", Map.of()));
        return rig.multipart.complete(new CompleteCommand(u.getUploadId(), u.getBucket(), u.getKey(), req, pre, whole,
                System.currentTimeMillis()));
    }

    byte[] read(ManifestEntity m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.reader.streamToOutput(m, out);
        return out.toByteArray();
    }

    byte[] readRange(ManifestEntity m, long start, long len) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rig.reader.streamRange(m, out, start, len);
        return out.toByteArray();
    }

    ManifestEntity object(String key) {
        return rig.catalog.findObject("bkt", key).orElseThrow();
    }

    static String md5Hex(byte[] d) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(d));
    }

    static String multipartEtag(List<byte[]> parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (byte[] p : parts) md.update(MessageDigest.getInstance("MD5").digest(p));
        return "\"" + HexFormat.of().formatHex(md.digest()) + "-" + parts.size() + "\"";
    }

    static String crc32cB64(byte[] d) {
        CRC32C c = new CRC32C();
        c.update(d, 0, d.length);
        return ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.crc32Bytes((int) c.getValue()));
    }

    long dataWrites() {
        return rig.metrics.get(rig.metrics.writes, Target.CUCKOO) + rig.metrics.get(rig.metrics.writes, Target.SMALL);
    }

    long physicalChunks() throws Exception {
        long n = 0;
        for (var b : rig.blobService.cuckooBlobsOf(pool)) n += rig.cache.fillStats(b).activeSlots();
        return n;
    }

    void gc0() {
        rig.gc.run(Options.defaults().withGrace(0));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void mixedPartsCompleteWithoutTouchingDataAndReadBackIdentically() throws Exception {
        List<byte[]> parts = List.of(
                bytes(3 * CHUNK + 100, 1),      // chunked, short last chunk
                bytes(500, 2),                  // small record
                bytes(2 * CHUNK, 3),            // chunked, exact
                new byte[0],                    // empty part
                bytes(CHUNK - 1, 5),            // just below the chunk size: small
                bytes(1000, 4));
        MultipartUpload u = create("big/object.bin");
        List<MultipartPart> rows = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) rows.add(upload(u, i + 1, parts.get(i)));
        assertEquals(parts.size(), rig.multipart.listParts(u.getUploadId(), "bkt", u.getKey(), 0, 100).parts().size());
        assertNull(rig.catalog.findObject("bkt", "big/object.bin").orElse(null), "invisible before Complete");

        long writes = dataWrites();
        long commitsBefore = rig.metrics.metaCommits.get();
        long dedup = rig.metrics.poolDedupHits.get();
        MultipartRepository.CompleteResult r = complete(u, rows.toArray(new MultipartPart[0]));
        assertEquals(writes, dataWrites(), "Complete writes no chunk and no small record: it is metadata only");
        assertTrue(rig.metrics.metaCommits.get() - commitsBefore <= 2, "a single metadata transaction");
        assertEquals(dedup, rig.metrics.poolDedupHits.get());

        byte[] whole = concat(parts);
        ManifestEntity m = object("big/object.bin");
        assertEquals(StorageKind.MULTIPART, m.getStorageKind());
        assertEquals(multipartEtag(parts), m.getEtag());
        assertEquals(m.getEtag(), r.etag());
        assertEquals(whole.length, m.getTotalBytes());
        assertEquals(crc32cB64(whole), m.getCrc32c(), "whole-object CRC32C combined from the parts' CRCs");
        assertEquals("application/x-test", m.getContentType());
        assertEquals(parts.size(), m.getSegments().size());
        assertArrayEquals(whole, read(m));

        // ranges: every pair of offsets around the part boundaries, and random ones
        long[] edges = new long[parts.size() + 1];
        for (int i = 0; i < parts.size(); i++) edges[i + 1] = edges[i] + parts.get(i).length;
        List<long[]> ranges = new ArrayList<>();
        for (long a : edges) for (long b : edges) {
            for (long da = -2; da <= 2; da++) for (long db = -2; db <= 2; db++) {
                long s = a + da, e = b + db;
                if (s >= 0 && e > s && e <= whole.length) ranges.add(new long[]{s, e - s});
            }
        }
        Random rnd = new Random(9);
        for (int i = 0; i < 200; i++) {
            long s = rnd.nextInt(whole.length);
            ranges.add(new long[]{s, 1 + rnd.nextInt((int) (whole.length - s))});
        }
        for (long[] range : ranges) {
            assertArrayEquals(Arrays.copyOfRange(whole, (int) range[0], (int) (range[0] + range[1])),
                    readRange(m, range[0], range[1]), "range " + range[0] + "+" + range[1]);
        }

        // per part
        for (int i = 0; i < parts.size(); i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(rig.reader.streamPart(m, i + 1, out));
            assertArrayEquals(parts.get(i), out.toByteArray(), "part " + (i + 1));
        }
        assertFalse(rig.reader.streamPart(m, 99, new ByteArrayOutputStream()));

        assertTrue(rig.verifier.verify(m).ok(), rig.verifier.verify(m).details().toString());
        assertEquals(0, rig.multipartRepo.countByState().get(MultipartUpload.State.OPEN));
    }

    @Test
    void checksumsAreDerivedFromStoredPartValues() throws Exception {
        List<byte[]> parts = List.of(bytes(3 * CHUNK + 7, 11), bytes(300, 12), bytes(2 * CHUNK + 1, 13), bytes(40, 14));
        byte[] whole = concat(parts);
        for (ChecksumAlgorithm alg : ChecksumAlgorithm.values()) {
            for (ChecksumType type : ChecksumType.values()) {
                if (type == ChecksumType.COMPOSITE && !alg.supportsComposite()) continue;
                if (type == ChecksumType.FULL_OBJECT && !alg.supportsFullObject()) continue;
                String key = "cs/" + alg + "/" + type;
                MultipartUpload u = create(key, alg, type);
                List<MultipartPart> rows = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) rows.add(upload(u, i + 1, parts.get(i)));
                long writes = dataWrites();
                complete(u, rows.toArray(new MultipartPart[0]));
                assertEquals(writes, dataWrites());
                ManifestEntity m = object(key);
                String expected;
                if (type == ChecksumType.COMPOSITE) {
                    expected = alg.composite(parts.stream().map(alg::compute).toList());
                } else {
                    expected = alg.encode(alg.compute(whole));
                }
                assertEquals(expected, m.getChecksumValue(), alg + " " + type);
                assertEquals(type, m.getChecksumType());
                assertEquals(alg, m.getChecksumAlgorithm());
                assertEquals(crc32cB64(whole), m.getCrc32c());
                assertArrayEquals(whole, read(m));
                assertTrue(rig.verifier.verify(m).ok(), rig.verifier.verify(m).details().toString());
            }
        }
    }

    @Test
    void wholeObjectChecksumHeaderOfCompleteIsVerified() throws Exception {
        List<byte[]> parts = List.of(bytes(3 * CHUNK, 21), bytes(77, 22));
        byte[] whole = concat(parts);
        MultipartUpload u = create("hdr", ChecksumAlgorithm.CRC32C, ChecksumType.FULL_OBJECT);
        MultipartPart p1 = upload(u, 1, parts.get(0));
        MultipartPart p2 = upload(u, 2, parts.get(1));
        String good = ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.CRC32C.compute(whole));
        String bad = ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.CRC32C.compute(parts.get(0)));
        MultipartException e = assertThrows(MultipartException.class, () -> complete(u,
                ManifestRepository.CommitPrecondition.NONE, Map.of(ChecksumAlgorithm.CRC32C, bad), p1, p2));
        assertEquals(MultipartException.Kind.BAD_DIGEST, e.kind());
        e = assertThrows(MultipartException.class, () -> complete(u,
                ManifestRepository.CommitPrecondition.NONE, Map.of(ChecksumAlgorithm.CRC32, good), p1, p2));
        assertEquals(MultipartException.Kind.INVALID_REQUEST, e.kind());
        assertNull(rig.catalog.findObject("bkt", "hdr").orElse(null));
        assertEquals(MultipartUpload.State.OPEN, rig.multipartRepo.find(u.getUploadId()).orElseThrow().getState());
        complete(u, ManifestRepository.CommitPrecondition.NONE, Map.of(ChecksumAlgorithm.CRC32C, good), p1, p2);
        assertEquals(good, object("hdr").getChecksumValue());
    }

    @Test
    void completeIsIdempotentForTheSamePartList() throws Exception {
        MultipartUpload u = create("idem");
        MultipartPart p1 = upload(u, 1, bytes(2 * CHUNK, 31));
        MultipartPart p2 = upload(u, 2, bytes(10, 32));
        MultipartRepository.CompleteResult first = complete(u, p1, p2);
        assertFalse(first.replayed());
        MultipartRepository.CompleteResult again = complete(u, p1, p2);
        assertTrue(again.replayed());
        assertEquals(first.etag(), again.etag());
        assertEquals(first.manifestId(), again.manifestId());
        MultipartException e = assertThrows(MultipartException.class, () -> complete(u, p1));
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD, e.kind(), "a different list is not an answer to the first Complete");
        e = assertThrows(MultipartException.class, () -> upload(u, 3, bytes(5, 33)));
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD, e.kind(), "a finished upload takes no more parts");
        assertEquals(first.manifestId(), object("idem").getId(), "the retry did not create a second version");
    }

    @Test
    void completeValidatesTheRequestedParts() throws Exception {
        MultipartUpload u = create("val");
        MultipartPart p1 = upload(u, 1, bytes(CHUNK + 5, 41));
        MultipartPart p2 = upload(u, 2, bytes(9, 42));
        MultipartPart p3 = upload(u, 3, bytes(2 * CHUNK, 43));
        MultipartPart ghost = new MultipartPart(7, "x", 1, md5Hex(new byte[1]), null, null, null, 0);

        assertEquals(MultipartException.Kind.INVALID_PART_ORDER,
                assertThrows(MultipartException.class, () -> complete(u, p2, p1)).kind());
        assertEquals(MultipartException.Kind.INVALID_PART,
                assertThrows(MultipartException.class, () -> complete(u, p1, ghost)).kind());
        MultipartPart wrongEtag = new MultipartPart(2, p2.manifestId(), p2.size(), md5Hex(new byte[3]), null, null, null, 0);
        assertEquals(MultipartException.Kind.INVALID_PART,
                assertThrows(MultipartException.class, () -> complete(u, p1, wrongEtag)).kind());
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD, assertThrows(MultipartException.class, () ->
                rig.multipart.complete(new CompleteCommand(u.getUploadId(), "bkt", "other-key",
                        List.of(new RequestedPart(1, null, Map.of())), ManifestRepository.CommitPrecondition.NONE,
                        Map.of(), 0))).kind(), "an upload id is valid only for its own key");
        assertNull(rig.catalog.findObject("bkt", "val").orElse(null), "failed Completes change nothing");
        assertEquals(3, rig.multipart.listParts(u.getUploadId(), "bkt", "val", 0, 10).parts().size());

        // part 2 is left out: it is discarded and its data becomes garbage
        long before = physicalChunks();
        complete(u, p1, p3);
        ManifestEntity m = object("val");
        assertEquals(List.of(1, 3), m.getSegments().stream().map(s -> s.partNumber()).toList());
        assertEquals(CHUNK + 5 + 2 * CHUNK, m.getTotalBytes());
        gc0();
        assertEquals(before, physicalChunks(), "part 2 was small: no chunk to free, the others stay");
        assertTrue(rig.verifier.verify(m).ok());
    }

    @Test
    void replacingAPartIsAtomicAndReleasesTheOldData() throws Exception {
        MultipartUpload u = create("replace");
        byte[] first = bytes(4 * CHUNK, 51);
        byte[] second = bytes(3 * CHUNK + 1, 52);
        upload(u, 1, first);
        assertEquals(4, physicalChunks());
        MultipartPart p = upload(u, 1, second);
        var page = rig.multipart.listParts(u.getUploadId(), "bkt", "replace", 0, 10);
        assertEquals(1, page.parts().size());
        assertEquals(md5Hex(second), page.parts().get(0).etag());
        assertEquals(second.length, page.parts().get(0).size());
        assertEquals(8, rig.chunks.stats().chunks(), "the retired part's chunks wait for the collector");
        assertEquals(4, rig.chunks.stats().gcQueue());
        gc0();
        assertEquals(4, physicalChunks());
        complete(u, p);
        assertArrayEquals(second, read(object("replace")));
    }

    @Test
    void identicalPartsDeduplicateAndKeepTheirReferences() throws Exception {
        byte[] data = bytes(3 * CHUNK, 61);
        MultipartUpload u = create("dedup");
        MultipartPart p1 = upload(u, 1, data);
        MultipartPart p2 = upload(u, 2, data);
        assertEquals(3, physicalChunks(), "the second part found its chunks in the index");
        assertEquals(6, rig.chunks.stats().refs());
        complete(u, p1, p2);
        assertEquals(6, rig.chunks.stats().refs(), "references were transferred, not added");
        assertArrayEquals(concat(List.of(data, data)), read(object("dedup")));
        rig.catalog.deleteObject("bkt", "dedup");
        assertEquals(0, rig.chunks.stats().refs());
        gc0();
        assertEquals(0, physicalChunks());
    }

    @Test
    void concurrentUploadsOfTheSamePartNumberLeaveExactlyOneWinner() throws Exception {
        MultipartUpload u = create("race");
        int writers = 8;
        List<byte[]> candidates = new ArrayList<>();
        for (int i = 0; i < writers; i++) candidates.add(bytes(i % 2 == 0 ? 2 * CHUNK + i : 100 + i, 70 + i));
        var exec = Executors.newFixedThreadPool(writers);
        try {
            List<Future<MultipartPart>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int idx = i;
                futures.add(exec.submit(() -> upload(u, 1, candidates.get(idx))));
            }
            for (var f : futures) f.get();
        } finally {
            exec.shutdownNow();
        }
        var page = rig.multipart.listParts(u.getUploadId(), "bkt", "race", 0, 10);
        assertEquals(1, page.parts().size(), "one row per part number");
        MultipartPart winner = page.parts().get(0);
        byte[] winning = candidates.stream().filter(c -> {
            try {
                return md5Hex(c).equals(winner.etag());
            } catch (Exception e) {
                return false;
            }
        }).findFirst().orElseThrow();
        assertEquals(winning.length, winner.size());
        complete(u, winner);
        assertArrayEquals(winning, read(object("race")), "etag, size and data come from the same upload");
        gc0();
        MetaInvariants.check(rig.store);
        long expectedChunks = winning.length / CHUNK + (winning.length % CHUNK == 0 ? 0 : 1);
        assertEquals(winning.length >= CHUNK ? expectedChunks : 0, physicalChunks(), "the losers' chunks were collected");
    }

    @Test
    void abortReleasesEverythingAndTheCollectorFreesTheData() throws Exception {
        MultipartUpload u = create("abort");
        upload(u, 1, bytes(5 * CHUNK, 81));
        upload(u, 2, bytes(300, 82));
        upload(u, 3, bytes(2 * CHUNK + 3, 83));
        assertEquals(8, physicalChunks());
        assertEquals(3, rig.multipart.abort(u.getUploadId(), "bkt", "abort"));
        assertEquals(0, rig.chunks.stats().refs());
        assertEquals(MultipartUpload.State.ABORTED, rig.multipartRepo.find(u.getUploadId()).orElseThrow().getState());
        gc0();
        assertEquals(0, physicalChunks());
        assertEquals(0, rig.chunks.stats().chunks());
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD,
                assertThrows(MultipartException.class, () -> rig.multipart.abort(u.getUploadId(), "bkt", "abort")).kind());
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD,
                assertThrows(MultipartException.class, () -> upload(u, 1, bytes(5, 1))).kind());
    }

    @Test
    void expiryAbortsStaleUploadsAndDropsOldRecords() throws Exception {
        MultipartUpload stale = create("stale");
        upload(stale, 1, bytes(3 * CHUNK, 91));
        MultipartUpload fresh = create("fresh");
        upload(fresh, 1, bytes(2 * CHUNK, 92));
        MultipartUpload done = create("done");
        complete(done, upload(done, 1, bytes(CHUNK, 93)));

        long now = System.currentTimeMillis();
        var none = rig.multipartRepo.cleanup(now, 3_600_000, 3_600_000);
        assertEquals(0, none.expired());
        // eight days later (the sweep takes `now` as an argument): every open upload is older than the seven-day limit
        var report = rig.multipartRepo.cleanup(now + 8L * 24 * 3600 * 1000, 7L * 24 * 3600 * 1000, 24L * 3600 * 1000);
        assertEquals(2, report.expired(), "both open uploads are older than the stale limit at that time");
        assertEquals(1, report.recordsRemoved(), "the completed record outlived its retention");
        assertEquals(0, report.failed());
        assertEquals(1, rig.chunks.stats().refs(), "only the completed object's chunk is still referenced");
        gc0();
        assertEquals(1, physicalChunks());
        assertEquals(MultipartException.Kind.NO_SUCH_UPLOAD,
                assertThrows(MultipartException.class, () -> upload(stale, 2, bytes(5, 2))).kind());
        var again = rig.multipartRepo.cleanup(now + 40L * 24 * 3600 * 1000, 7L * 24 * 3600 * 1000, 24L * 3600 * 1000);
        assertEquals(2, again.recordsRemoved(), "the ABORTED records go after their retention too");
        assertTrue(rig.multipartRepo.countByState().values().stream().allMatch(c -> c == 0));
    }

    @Test
    void partsSurviveARestartAndTheUploadIsResumable() throws Exception {
        MultipartUpload u = create("restart");
        byte[] a = bytes(3 * CHUNK + 5, 101);
        byte[] b = bytes(250, 102);
        upload(u, 1, a);
        upload(u, 2, b);
        rig.reopen();
        pool = rig.pools.findByName("bkt").orElseThrow();
        var page = rig.multipart.listParts(u.getUploadId(), "bkt", "restart", 0, 10);
        assertEquals(2, page.parts().size());
        byte[] c = bytes(CHUNK, 103);
        MultipartPart p3 = upload(u, 3, c);
        complete(u, page.parts().get(0), page.parts().get(1), p3);
        assertArrayEquals(concat(List.of(a, b, c)), read(object("restart")));
        rig.reopen();
        assertArrayEquals(concat(List.of(a, b, c)), read(object("restart")));
        assertTrue(rig.verifier.verify(object("restart")).ok());
    }

    @Test
    void dataWrittenButNotCommittedLeavesNoPartAndTheSweepCleansTheStrays() throws Exception {
        MultipartUpload u = create("crash");
        MultipartPart kept = upload(u, 1, bytes(CHUNK, 111));
        // the part's data is written, then "the process dies" before the commit transaction
        ManifestEntity staged = rig.writer.stagePart(new ByteArrayInputStream(bytes(4 * CHUNK, 112)), pool, UploadChecksums.none());
        ManifestEntity stagedSmall = rig.writer.stagePart(new ByteArrayInputStream(bytes(200, 113)), pool, UploadChecksums.none());
        rig.writer.abortStaged(staged);
        rig.writer.abortStaged(stagedSmall);
        assertEquals(1, rig.multipart.listParts(u.getUploadId(), "bkt", "crash", 0, 10).parts().size(), "no part appeared");
        assertEquals(1, rig.chunks.stats().chunks());
        assertEquals(5, physicalChunks(), "the strays are physically there");
        rig.gc.sweep(pool);
        assertEquals(1, physicalChunks(), "the sweep freed the chunks no index entry points to");
        complete(u, kept);
        assertEquals(CHUNK, object("crash").getTotalBytes());
    }

    @Test
    void multipartObjectIsRetiredLikeAnyOtherAndItsSmallRecordsAreReclaimed() throws Exception {
        MultipartUpload u = create("gone");
        MultipartPart p1 = upload(u, 1, bytes(3 * CHUNK, 121));
        MultipartPart p2 = upload(u, 2, bytes(600, 122));
        MultipartPart p3 = upload(u, 3, bytes(700, 123));
        complete(u, p1, p2, p3);
        var smalls = rig.blobs.findByPoolId(pool.getId()).stream()
                .filter(b -> b.getKind() == BlobKind.SMALL).toList();
        assertEquals(1, smalls.size());
        assertEquals(2, rig.small(smalls.get(0)).stats().liveRecords());

        // overwriting with a plain PUT retires the multipart version
        rig.writer.storeStream(new ByteArrayInputStream(bytes(10, 124)), pool, "gone", "text/plain");
        assertEquals(0, rig.manifests.findByBlobId(smalls.get(0).getId(), true).stream()
                .filter(m -> m.getStorageKind() == StorageKind.MULTIPART).count());
        var report = rig.gc.run(Options.defaults().withGrace(0));
        assertEquals(3, report.chunksFreed);
        assertEquals(1, report.smallRecordsMarked, "one retired manifest had its small records flipped to DELETED");
        assertEquals(1, rig.small(smalls.get(0)).stats().liveRecords(), "only the new 10-byte object is left");
        assertEquals(0, physicalChunks());
    }

    @Test
    void deletingTheBucketDiscardsOpenUploads() throws Exception {
        MultipartUpload u = create("pending");
        upload(u, 1, bytes(2 * CHUNK, 131));
        upload(u, 2, bytes(100, 132));
        MultipartUpload done = create("complete-me");
        complete(done, upload(done, 1, bytes(CHUNK, 133)));
        assertThrows(IllegalStateException.class, () -> rig.poolService.deleteBucket("bkt"), "an object is still there");
        rig.catalog.deleteObject("bkt", "complete-me");
        rig.poolService.deleteBucket("bkt");
        assertEquals(0, rig.multipartRepo.countByState().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(rig.multipartRepo.find(u.getUploadId()).isEmpty());
        pool = rig.bucket("bkt");   // the name is reusable and starts empty
        assertFalse(rig.multipartRepo.hasOpenUploads("bkt"));
    }

    @Test
    void aFailedPreconditionLeavesTheUploadOpenAndItsPartsIntact() throws Exception {
        rig.writer.storeStream(new ByteArrayInputStream(bytes(10, 1)), pool, "exists", "text/plain");
        MultipartUpload u = create("exists");
        MultipartPart p = upload(u, 1, bytes(2 * CHUNK, 2));
        var ifNone = new ManifestRepository.CommitPrecondition(true, null);
        assertThrows(ManifestRepository.CommitPreconditionException.class,
                () -> complete(u, ifNone, Map.of(), p));
        assertEquals(MultipartUpload.State.OPEN, rig.multipartRepo.find(u.getUploadId()).orElseThrow().getState());
        assertEquals(1, rig.multipart.listParts(u.getUploadId(), "bkt", "exists", 0, 10).parts().size());
        assertEquals(10, object("exists").getTotalBytes(), "the existing object was not touched");
        complete(u, p);                                    // without the condition the same part list is accepted
        assertEquals(2 * CHUNK, object("exists").getTotalBytes());
    }

    @Test
    void concurrentCompletesOfOneUploadYieldOneObjectAndReplayedAnswers() throws Exception {
        MultipartUpload u = create("twice");
        MultipartPart p1 = upload(u, 1, bytes(3 * CHUNK, 3));
        MultipartPart p2 = upload(u, 2, bytes(70, 4));
        var exec = Executors.newFixedThreadPool(4);
        try {
            List<Future<MultipartRepository.CompleteResult>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) fs.add(exec.submit(() -> complete(u, p1, p2)));
            List<MultipartRepository.CompleteResult> rs = new ArrayList<>();
            for (var f : fs) rs.add(f.get());
            assertEquals(1, rs.stream().filter(r -> !r.replayed()).count());
            assertEquals(1, rs.stream().map(MultipartRepository.CompleteResult::manifestId).distinct().count());
        } finally {
            exec.shutdownNow();
        }
        assertEquals(1, rig.catalog.listAll().size());
    }

    @Test
    void anUploadWithManyPartsCompletesInOneQuickTransaction() throws Exception {
        MultipartUpload u = create("many");
        int parts = 2000;
        List<MultipartPart> rows = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        for (int i = 1; i <= parts; i++) {
            byte[] d = bytes(i % 7 == 0 ? CHUNK + i % 50 : 20 + i % 50, i);
            data.add(d);
            rows.add(upload(u, i, d));
        }
        long t0 = System.nanoTime();
        complete(u, rows.toArray(new MultipartPart[0]));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, "Complete of " + parts + " parts took " + ms + " ms");
        ManifestEntity m = object("many");
        assertEquals(parts, m.getSegments().size());
        assertArrayEquals(concat(data), read(m));
        assertEquals(multipartEtag(data), m.getEtag());
    }

    @Test
    void theMinimumPartSizeAppliesToEveryPartButTheLast() throws Exception {
        var strict = new MultipartService(
                new MetaStoreMultipartRepository(
                        MetaStoreProvider.of(rig.store), 1000),
                rig.pools, rig.writer, rig.reader);
        MultipartUpload u = create("strict");
        MultipartPart small = upload(u, 1, bytes(999, 1));
        MultipartPart ok = upload(u, 2, bytes(1000, 2));
        MultipartPart last = upload(u, 3, bytes(5, 3));
        var tooSmall = assertThrows(MultipartException.class, () -> strictComplete(strict, u, small, last));
        assertEquals(MultipartException.Kind.ENTITY_TOO_SMALL, tooSmall.kind());
        assertEquals(999, tooSmall.proposedSize());
        assertEquals(1000, tooSmall.minSizeAllowed());
        assertEquals(1, tooSmall.partNumber());
        assertNull(rig.catalog.findObject("bkt", "strict").orElse(null));
        assertEquals(MultipartUpload.State.OPEN, rig.multipartRepo.find(u.getUploadId()).orElseThrow().getState());
        strictComplete(strict, u, ok, last);                       // the small last part is exempt
        assertEquals(1005, object("strict").getTotalBytes());
    }

    private void strictComplete(MultipartService svc, MultipartUpload u, MultipartPart... parts) {
        List<RequestedPart> req = new ArrayList<>();
        for (MultipartPart p : parts) req.add(new RequestedPart(p.partNumber(), p.etag(), Map.of()));
        svc.complete(new CompleteCommand(u.getUploadId(), u.getBucket(), u.getKey(), req,
                ManifestRepository.CommitPrecondition.NONE, Map.of(), System.currentTimeMillis()));
    }
}
