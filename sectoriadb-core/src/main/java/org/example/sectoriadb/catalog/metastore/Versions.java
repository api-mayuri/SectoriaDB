package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The version history of keys in versioned buckets (docs/architecture/versioning.md).
 *
 * <ul>
 *   <li>{@value #VERSIONS}: {@code (bucket, key, Long.MAX_VALUE - seq)} → {@code kind(1) | time(8) | versionId | 0 | manifestId}:
 *       the versions and delete markers of a key, newest first;</li>
 *   <li>{@value #VERSION_IDS}: {@code (bucket, key, versionId)} → {@code seq}: a version by its id;</li>
 *   <li>{@value #SEQ}: the counter of the sequence numbers (several versions of one key can be written in one group commit,
 *       so the transaction id is not enough).</li>
 * </ul>
 * The current version of a key that was written without a version id (versioning never enabled, or {@code Suspended})
 * is not a row here: it is the newest version by construction, and becomes a row with the id {@value #NULL_ID} only
 * when a newer version pushes it back.
 */
final class Versions {

    static final String VERSIONS = "versions";
    static final String VERSION_IDS = "version_ids";
    static final String SEQ = "version_seq";
    static final String NULL_ID = "null";
    static final byte VERSION = 0, MARKER = 1;
    private static final byte[] SEQ_KEY = {0};

    private Versions() {
    }

    /** One row of the history. {@code manifestId} is null for a delete marker. */
    record Row(String bucket, String key, long seq, byte kind, long timeMillis, String versionId, String manifestId) {
        boolean marker() {
            return kind == MARKER;
        }
    }

    static long nextSeq(WriteTxn tx) {
        BTree t = tx.tree(SEQ);
        long next = t.get(SEQ_KEY).map(v -> ByteBuffer.wrap(v).getLong()).orElse(0L) + 1;
        t.put(SEQ_KEY, ByteBuffer.allocate(8).putLong(next).array());
        return next;
    }

    static String newMarkerId() {
        return UUID.randomUUID().toString();
    }

    private static byte[] rowKey(String bucket, String key, long seq) {
        return Keys.builder().string(bucket).string(key).longUnsigned(Long.MAX_VALUE - seq).build();
    }

    private static byte[] idKey(String bucket, String key, String versionId) {
        return Keys.builder().string(bucket).string(key).string(versionId).build();
    }

    static Row add(WriteTxn tx, String bucket, String key, byte kind, String versionId, String manifestId, long timeMillis) {
        long seq = nextSeq(tx);
        byte[] vid = versionId.getBytes(StandardCharsets.UTF_8);
        byte[] mid = manifestId == null ? new byte[0] : manifestId.getBytes(StandardCharsets.UTF_8);
        byte[] value = ByteBuffer.allocate(1 + 8 + vid.length + 1 + mid.length).put(kind).putLong(timeMillis).put(vid)
                .put((byte) 0).put(mid).array();
        tx.tree(VERSIONS).put(rowKey(bucket, key, seq), value);
        tx.tree(VERSION_IDS).put(idKey(bucket, key, versionId), ByteBuffer.allocate(8).putLong(seq).array());
        return new Row(bucket, key, seq, kind, timeMillis, versionId, manifestId);
    }

    static void remove(WriteTxn tx, Row r) {
        tx.tree(VERSIONS).delete(rowKey(r.bucket(), r.key(), r.seq()));
        tx.tree(VERSION_IDS).delete(idKey(r.bucket(), r.key(), r.versionId()));
    }

    private static Row decode(byte[] k, byte[] v) {
        Keys.Reader r = new Keys.Reader(k);
        String bucket = r.string();
        String key = r.string();
        long seq = Long.MAX_VALUE - r.longUnsigned();
        ByteBuffer b = ByteBuffer.wrap(v);
        byte kind = b.get();
        long time = b.getLong();
        int sep = -1;
        for (int i = 9; i < v.length; i++) {
            if (v[i] == 0) {
                sep = i;
                break;
            }
        }
        if (sep < 0) throw new IllegalStateException("corrupt version row of " + bucket + "/" + key);
        String vid = new String(v, 9, sep - 9, StandardCharsets.UTF_8);
        String mid = sep + 1 < v.length ? new String(v, sep + 1, v.length - sep - 1, StandardCharsets.UTF_8) : null;
        return new Row(bucket, key, seq, kind, time, vid, kind == MARKER ? null : mid);
    }

    static Optional<Row> byId(ReadTxn tx, String bucket, String key, String versionId) {
        return tx.tree(VERSION_IDS).get(idKey(bucket, key, versionId)).map(v -> ByteBuffer.wrap(v).getLong())
                .flatMap(seq -> tx.tree(VERSIONS).get(rowKey(bucket, key, seq)).map(v -> decode(rowKey(bucket, key, seq), v)));
    }

    /** The rows of one key, newest first. */
    static List<Row> ofKey(ReadTxn tx, String bucket, String key) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = tx.tree(VERSIONS).scanPrefix(Keys.builder().string(bucket).string(key).build())) {
            while (c.next()) out.add(decode(c.key(), c.value()));
        }
        return out;
    }

    /** The newest row of a key, if any. */
    static Optional<Row> newest(ReadTxn tx, String bucket, String key) {
        try (Cursor c = tx.tree(VERSIONS).scanPrefix(Keys.builder().string(bucket).string(key).build())) {
            return c.next() ? Optional.of(decode(c.key(), c.value())) : Optional.empty();
        }
    }

    /** True if the bucket has any version row (versions or markers): such a bucket is not empty. */
    static boolean any(ReadTxn tx, String bucket) {
        try (Cursor c = tx.tree(VERSIONS).scanPrefix(Keys.builder().string(bucket).build())) {
            return c.next();
        }
    }

    /** Rows of a bucket in key order (keys ascending, versions of a key newest first), from {@code fromKey} inclusive. */
    static Cursor scan(ReadTxn tx, String bucket, String prefix) {
        return tx.tree(VERSIONS).scanPrefix(Keys.builder().string(bucket).stringPrefix(prefix == null ? "" : prefix).build());
    }

    static Row decodeRow(Cursor c) {
        return decode(c.key(), c.value());
    }

    /** The version id a manifest is known by: its own, or {@value #NULL_ID}. */
    static String idOf(ManifestEntity m) {
        return m.getVersionId() == null ? NULL_ID : m.getVersionId();
    }
}
