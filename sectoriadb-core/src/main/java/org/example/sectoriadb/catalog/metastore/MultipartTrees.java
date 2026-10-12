package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trees and key encodings of the multipart state (doc 13):
 * <ul>
 *   <li>{@code mpu_uploads}: {@code uploadId -> MultipartUpload} (JSON), every state;</li>
 *   <li>{@code mpu_by_bucket}: {@code (bucket, key, initiatedAt u64, uploadId) -> empty}, only OPEN uploads, so ListMultipartUploads
 *       is a range scan already ordered by key and then by initiation time;</li>
 *   <li>{@code mpu_parts}: {@code (uploadId, partNumber u64) -> MultipartPart} (JSON), parts of OPEN uploads.</li>
 * </ul>
 */
final class MultipartTrees {

    static final String UPLOADS = "mpu_uploads";
    static final String BY_BUCKET = "mpu_by_bucket";
    static final String PARTS = "mpu_parts";

    private MultipartTrees() {
    }

    static byte[] uploadKey(String uploadId) {
        return Keys.of(uploadId);
    }

    static byte[] byBucketKey(String bucket, String key, long initiatedAt, String uploadId) {
        return Keys.builder().string(bucket).string(key).longUnsigned(initiatedAt).string(uploadId).build();
    }

    static byte[] byBucketKey(MultipartUpload u) {
        return byBucketKey(u.getBucket(), u.getKey(), u.getInitiatedAt(), u.getUploadId());
    }

    static byte[] partKey(String uploadId, int partNumber) {
        return Keys.builder().string(uploadId).longUnsigned(partNumber).build();
    }

    static Optional<MultipartUpload> upload(ReadTxn tx, String uploadId) {
        return tx.tree(UPLOADS).get(uploadKey(uploadId)).map(v -> JsonValues.read(v, 0, v.length, MultipartUpload.class));
    }

    static void putUpload(WriteTxn tx, MultipartUpload u) {
        tx.tree(UPLOADS).put(uploadKey(u.getUploadId()), JsonValues.write(u));
    }

    static void indexOpen(WriteTxn tx, MultipartUpload u) {
        tx.tree(BY_BUCKET).put(byBucketKey(u), Trees.EMPTY);
    }

    static void unindex(WriteTxn tx, MultipartUpload u) {
        tx.tree(BY_BUCKET).delete(byBucketKey(u));
    }

    static Optional<MultipartPart> part(ReadTxn tx, String uploadId, int partNumber) {
        return tx.tree(PARTS).get(partKey(uploadId, partNumber)).map(MultipartTrees::decodePart);
    }

    static MultipartPart decodePart(byte[] v) {
        return JsonValues.read(v, 0, v.length, MultipartPart.class);
    }

    static void putPart(WriteTxn tx, String uploadId, MultipartPart p) {
        tx.tree(PARTS).put(partKey(uploadId, p.partNumber()), JsonValues.write(p));
    }

    /** All parts of an upload in part number order. */
    static List<MultipartPart> parts(ReadTxn tx, String uploadId) {
        List<MultipartPart> out = new ArrayList<>();
        try (Cursor c = tx.tree(PARTS).scanPrefix(Keys.of(uploadId))) {
            while (c.next()) out.add(decodePart(c.value()));
        }
        return out;
    }

    static void deleteParts(WriteTxn tx, String uploadId) {
        BTree parts = tx.tree(PARTS);
        List<byte[]> keys = new ArrayList<>();
        try (Cursor c = parts.scanPrefix(Keys.of(uploadId))) {
            while (c.next()) keys.add(c.key());
        }
        for (byte[] k : keys) parts.delete(k);
    }

    static List<MultipartUpload> allUploads(ReadTxn tx) {
        List<MultipartUpload> out = new ArrayList<>();
        try (Cursor c = tx.tree(UPLOADS).scan()) {
            while (c.next()) out.add(JsonValues.read(c.value(), 0, c.value().length, MultipartUpload.class));
        }
        return out;
    }

    /**
     * Bucket deletion: every upload of the bucket in any state disappears with its parts. The part manifests are
     * removed by the pool purge (they are indexed by pool), the chunk index with the pool's whole chunk index.
     */
    static int purgeBucket(WriteTxn tx, String bucket) {
        int n = 0;
        for (MultipartUpload u : allUploads(tx)) {
            if (!bucket.equals(u.getBucket())) continue;
            deleteParts(tx, u.getUploadId());
            unindex(tx, u);
            tx.tree(UPLOADS).delete(uploadKey(u.getUploadId()));
            n++;
        }
        return n;
    }
}
