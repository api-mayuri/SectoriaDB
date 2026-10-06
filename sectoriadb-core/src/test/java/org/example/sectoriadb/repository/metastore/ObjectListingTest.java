package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.MetaStoreOptions;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.model.PoolEntity;
import org.example.sectoriadb.model.StorageKind;
import org.example.sectoriadb.repository.ManifestRepository;
import org.example.sectoriadb.repository.ManifestRepository.ObjectListing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ListObjects against a naive reference computed in the test: thousands of keys with unicode, supplementary
 * characters, NULs and characters that sort around '/', several delimiters, prefixes and page sizes, v1 style
 * pagination (the next marker is fed back as the lower bound).
 */
class ObjectListingTest {

    static final String B = "bkt";
    static final String[] ALPHABET = {"a", "b", "c", "/", "/", ".", "-", "0", " ", "~", "é", "中", "😀", "\u0000", "�"};

    @TempDir
    Path dir;

    MetaStore store;
    ManifestRepository repo;
    List<String> keys;   // keys of bucket B in S3 order (UTF-8 binary)

    /** S3 orders keys by their UTF-8 bytes (not by UTF-16 units like String.compareTo). */
    static int cmp(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void populate() {
        store = MetaStore.open(dir.resolve("l.db"), MetaStoreOptions.defaults().fsync(false));
        repo = new MetaStoreManifestRepository(MetaStoreProvider.of(store));
        PoolEntity pool = new PoolEntity("p-" + B, B, "/x", Instant.now());
        PoolEntity other = new PoolEntity("p-o", B + "2", "/x", Instant.now());
        PoolEntity dash = new PoolEntity("p-d", B + "-", "/x", Instant.now());

        Random r = new Random(42);
        Set<String> set = new TreeSet<>(ObjectListingTest::cmp);
        // adversarial fixed keys: neighbours of '/' (0x2E '.', 0x30 '0', 0x2D '-'), prefixes of each other, folder markers
        set.addAll(List.of("a", "a/", "a/b", "a/b/", "a/b/c", "a-b", "a.b", "a0", "a b", "a~", "a/\u0000", "a\u0000", "a\u0000/",
                "é", "é/x", "中文/x", "😀/x", "�/x", "�", "dir/", "dir//", "dir//x", "dir/x", "dir/y/z"));
        while (set.size() < 4000) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + r.nextInt(7);
            for (int i = 0; i < n; i++) sb.append(ALPHABET[r.nextInt(ALPHABET.length)]);
            set.add(sb.toString());
        }
        keys = new ArrayList<>(set);
        store.writeVoid(tx -> {
            Trees.putPool(tx, pool);
            Trees.putPool(tx, other);
            Trees.putPool(tx, dash);
            int i = 0;
            for (String key : keys) {
                put(tx, pool, "m" + i++, key);
                if (i % 3 == 0) put(tx, other, "o" + i, key);      // same keys in a bucket whose name starts with B
                if (i % 5 == 0) put(tx, dash, "d" + i, key);
            }
        });
    }

    private static void put(org.example.sectoriadb.metastore.WriteTxn tx, PoolEntity pool, String id, String key) {
        ManifestEntity m = new ManifestEntity();
        m.setId(id);
        m.setPoolId(pool.getId());
        m.setStorageKind(StorageKind.EMPTY);
        m.setBucketName(pool.getName());
        m.setObjectKey(key);
        m.setEtag("\"" + id + "\"");
        m.setTotalBytes(key.length());
        m.setCreatedAt(Instant.parse("2026-03-04T05:06:07Z"));
        Trees.putManifest(tx, m);
        tx.tree(Trees.OBJECTS).put(Trees.objectKey(pool.getName(), key), Trees.bytes(id));
    }

    @AfterEach
    void close() {
        store.close();
    }

    // ── reference ────────────────────────────────────────────────────────────

    record Ref(List<String> keys, List<String> prefixes, boolean truncated, String next) {
    }

    private record Entry(String text, boolean prefix) {
    }

    Ref reference(String prefix, String delimiter, String after, int max) {
        List<Entry> entries = new ArrayList<>();
        boolean afterIsCommonPrefix = false;
        if (after != null && delimiter != null && !delimiter.isEmpty() && after.startsWith(prefix)) {
            int d = after.indexOf(delimiter, prefix.length());
            afterIsCommonPrefix = d >= 0 && d + delimiter.length() == after.length();
        }
        for (String key : keys) {
            if (!key.startsWith(prefix)) continue;
            if (after != null && cmp(key, after) <= 0) continue;               // StartAfter / Marker are exclusive, key based
            if (after != null && afterIsCommonPrefix && key.startsWith(after)) continue;   // that folder was already returned
            Entry e = new Entry(key, false);
            if (delimiter != null && !delimiter.isEmpty()) {
                int d = key.indexOf(delimiter, prefix.length());
                if (d >= 0) e = new Entry(key.substring(0, d + delimiter.length()), true);
            }
            if (e.prefix() && !entries.isEmpty() && entries.get(entries.size() - 1).equals(e)) continue;   // one per common prefix
            entries.add(e);
        }
        boolean truncated = entries.size() > max;
        List<Entry> page = entries.subList(0, Math.min(max, entries.size()));
        List<String> ks = new ArrayList<>();
        List<String> ps = new ArrayList<>();
        for (Entry e : page) (e.prefix() ? ps : ks).add(e.text());
        return new Ref(ks, ps, truncated, page.isEmpty() ? null : page.get(page.size() - 1).text());
    }

    private void assertSame(Ref ref, ObjectListing got, String what) {
        assertEquals(ref.keys(), got.objects().stream().map(ManifestRepository.ObjectSummary::key).toList(), what + " keys");
        assertEquals(ref.prefixes(), got.commonPrefixes(), what + " prefixes");
        assertEquals(ref.truncated(), got.truncated(), what + " truncated");
        if (!got.objects().isEmpty() || !got.commonPrefixes().isEmpty()) assertEquals(ref.next(), got.nextMarker(), what + " next");
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void orderIsUtf8BinaryOrder() {
        ObjectListing all = repo.listObjects(B, "", null, null, 10_000);
        assertFalse(all.truncated());
        assertEquals(keys, all.objects().stream().map(ManifestRepository.ObjectSummary::key).toList());
        // the supplementary character (4-byte UTF-8) sorts after U+FFFD (3 bytes), unlike with String.compareTo
        assertTrue(keys.indexOf("😀/x") > keys.indexOf("�/x"));
    }

    @Test
    void summariesCarryTheListedAttributes() {
        ManifestRepository.ObjectSummary s = repo.listObjects(B, "dir/y/", null, null, 10).objects().get(0);
        assertEquals("dir/y/z", s.key());
        assertEquals(Instant.parse("2026-03-04T05:06:07Z"), s.lastModified());
        assertEquals("dir/y/z".length(), s.size());
        assertTrue(s.etag().startsWith("\"m"));
    }

    @Test
    void flatListingWithPrefixesAndArbitraryStartAfter() {
        Random r = new Random(1);
        for (int i = 0; i < 300; i++) {
            String prefix = switch (r.nextInt(4)) {
                case 0 -> "";
                case 1 -> {
                    String k = keys.get(r.nextInt(keys.size()));
                    int n = Math.min(2, k.length());
                    if (n > 0 && Character.isHighSurrogate(k.charAt(n - 1))) n--;   // a prefix is a valid string, never half a pair
                    yield k.substring(0, n);
                }
                case 2 -> keys.get(r.nextInt(keys.size()));
                default -> "zz" + i;
            };
            String after = switch (r.nextInt(3)) {
                case 0 -> null;
                case 1 -> keys.get(r.nextInt(keys.size()));
                default -> keys.get(r.nextInt(keys.size())) + (r.nextBoolean() ? "\u0000" : "a");   // not a key
            };
            int max = 1 + r.nextInt(50);
            assertSame(reference(prefix, null, after, max), repo.listObjects(B, prefix, null, after, max),
                    "flat prefix='" + prefix.replace("\u0000", "\\0") + "' after='" + String.valueOf(after).replace("\u0000", "\\0") + "' max=" + max);
        }
    }

    @Test
    void paginationWalksEveryEntryExactlyOnce() {
        for (String delimiter : new String[]{null, "/", "-", "//", "a", "é"}) {
            for (String prefix : new String[]{"", "a", "a/", "dir/", "é", "b/"}) {
                for (int pageSize : new int[]{1, 7, 100, 1000}) {
                    List<String> seen = new ArrayList<>();
                    String after = null;
                    int pages = 0;
                    while (true) {
                        Ref ref = reference(prefix, delimiter, after, pageSize);
                        ObjectListing got = repo.listObjects(B, prefix, delimiter, after, pageSize);
                        String what = "delim=" + delimiter + " prefix=" + prefix + " page=" + pageSize + " after="
                                + String.valueOf(after).replace("\u0000", "\\0");
                        assertSame(ref, got, what);
                        got.objects().forEach(o -> seen.add(o.key()));
                        seen.addAll(got.commonPrefixes());
                        pages++;
                        if (!got.truncated()) break;
                        after = got.nextMarker();
                        assertNotNull(after);
                        if (pageSize == 1) assertTrue(pages < 5000, "makes progress");
                    }
                    assertEquals(seen.size(), new java.util.HashSet<>(seen).size(), "no entry twice: " + delimiter + " " + prefix);
                    Ref whole = reference(prefix, delimiter, null, Integer.MAX_VALUE);
                    assertEquals(whole.keys().size() + whole.prefixes().size(), seen.size(), "all entries: " + delimiter + " " + prefix);
                }
            }
        }
    }

    @Test
    void delimiterListingReturnsFoldersAndSkipsTheirContents() {
        ObjectListing root = repo.listObjects(B, "", "/", null, 100_000);
        assertFalse(root.truncated());
        assertTrue(root.commonPrefixes().containsAll(List.of("a/", "dir/", "é/", "中文/", "😀/", "�/")), root.commonPrefixes().toString());
        assertFalse(root.objects().stream().anyMatch(o -> o.key().contains("/")), "nothing below a folder is listed");
        ObjectListing dir = repo.listObjects(B, "dir/", "/", null, 1000);
        assertEquals(List.of("dir/", "dir/x"), dir.objects().stream().map(ManifestRepository.ObjectSummary::key).toList());
        assertEquals(List.of("dir//", "dir/y/"), dir.commonPrefixes());
        // a continuation that starts at a common prefix does not return the contents of that prefix again
        ObjectListing after = repo.listObjects(B, "", "/", "a/", 1000);
        assertFalse(after.commonPrefixes().contains("a/"));
        assertFalse(after.objects().stream().anyMatch(o -> o.key().startsWith("a/")));
    }

    @Test
    void bucketsAreIsolatedFromBucketsWithTheSameNamePrefix() {
        // "bkt2" and "bkt-" hold some of the same keys, none of them may leak into "bkt" and vice versa
        assertEquals(keys.size(), repo.listObjects(B, "", null, null, 100_000).objects().size());
        assertTrue(repo.listObjects(B + "2", "", null, null, 100_000).objects().size() < keys.size());
        assertEquals(0, repo.listObjects("bk", "", null, null, 10).objects().size());
        assertEquals(0, repo.listObjects(B + "3", "", null, null, 10).objects().size());
    }

    @Test
    void maxKeysZeroAndEmptyRanges() {
        ObjectListing none = repo.listObjects(B, "", null, null, 0);
        assertTrue(none.objects().isEmpty());
        assertFalse(none.truncated());     // S3: MaxKeys=0 -> empty, IsTruncated=false
        assertTrue(none.commonPrefixes().isEmpty());
        assertFalse(repo.listObjects(B, "", "/", null, 0).truncated());
        assertFalse(repo.listObjects(B, "zzz-not-there", "/", null, 10).truncated());
        // beyond every key in UTF-8 order (U+FFFF sorts below 4-byte characters)
        assertTrue(repo.listObjects(B, "", null, "\uDBFF\uDFFF\uDBFF\uDFFF", 10).objects().isEmpty());
    }

    @Test
    void anOverwrittenKeyIsListedOnceWithItsNewestManifest() {
        PoolEntity pool = new PoolEntity("p-" + B, B, "/x", Instant.now());
        ManifestEntity m = new ManifestEntity();
        m.setId("newest");
        m.setPoolId(pool.getId());
        m.setStorageKind(StorageKind.EMPTY);
        m.setBucketName(B);
        m.setObjectKey("dir/x");
        m.setEtag("\"new\"");
        m.setCreatedAt(Instant.now());
        repo.commitObject(m);
        List<ManifestRepository.ObjectSummary> l = repo.listObjects(B, "dir/x", null, null, 10).objects();
        assertEquals(1, l.size());
        assertEquals("newest", l.get(0).manifestId());
        assertEquals("\"new\"", l.get(0).etag());
    }
}
