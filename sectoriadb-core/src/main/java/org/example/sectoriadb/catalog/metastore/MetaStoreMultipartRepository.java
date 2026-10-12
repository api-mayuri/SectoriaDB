package org.example.sectoriadb.catalog.metastore;

import org.example.sectoriadb.catalog.ManifestEntity;
import org.example.sectoriadb.catalog.ManifestRepository;
import org.example.sectoriadb.catalog.MultipartException;
import org.example.sectoriadb.catalog.MultipartException.Kind;
import org.example.sectoriadb.catalog.MultipartPart;
import org.example.sectoriadb.catalog.MultipartRepository;
import org.example.sectoriadb.catalog.MultipartUpload;
import org.example.sectoriadb.catalog.PartSegment;
import org.example.sectoriadb.catalog.StorageKind;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.CrcCombine;
import org.example.sectoriadb.metastore.BTree;
import org.example.sectoriadb.metastore.Cursor;
import org.example.sectoriadb.metastore.Keys;
import org.example.sectoriadb.metastore.MetaStore;
import org.example.sectoriadb.metastore.ReadTxn;
import org.example.sectoriadb.metastore.WriteTxn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link MultipartRepository} over the {@code mpu_uploads}, {@code mpu_by_bucket} and {@code mpu_parts} trees and the
 * regular manifest and chunk-index trees (doc 13). Every public method is one atomic group-commit body.
 */
public class MetaStoreMultipartRepository implements MultipartRepository {

    private static final Logger log = LoggerFactory.getLogger(MetaStoreMultipartRepository.class);

    /** Strict canonical UUID: anything else cannot be an upload id of ours. */
    private static final Pattern UPLOAD_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final MetaStoreProvider stores;

    public MetaStoreMultipartRepository(MetaStoreProvider stores) {
        this.stores = stores;
    }

    private MetaStore store() {
        return stores.get();
    }

    private static MultipartException noSuchUpload(String uploadId) {
        return new MultipartException(Kind.NO_SUCH_UPLOAD, "The specified upload does not exist: " + uploadId);
    }

    /** The upload if it exists, belongs to this bucket and key and is OPEN. */
    private static MultipartUpload openUpload(ReadTxn tx, String uploadId, String bucket, String key) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) throw noSuchUpload(uploadId);
        MultipartUpload up = MultipartTrees.upload(tx, uploadId).orElseThrow(() -> noSuchUpload(uploadId));
        if (!up.getBucket().equals(bucket) || !up.getKey().equals(key) || up.getState() != MultipartUpload.State.OPEN) {
            throw noSuchUpload(uploadId);
        }
        return up;
    }

    // ------------------------------------------------------------------ create / find

    @Override
    public MultipartUpload create(MultipartUpload upload) {
        if (upload.getPoolId() == null || upload.getBucket() == null || upload.getKey() == null) {
            throw new IllegalArgumentException("poolId, bucket and key are required");
        }
        return store().writeGrouped(tx -> {
            if (!tx.tree(Trees.POOLS).containsKey(Trees.idKey(upload.getPoolId()))) {
                throw new ManifestRepository.PoolNotFoundException(upload.getPoolId());
            }
            upload.setUploadId(UUID.randomUUID().toString());
            upload.setState(MultipartUpload.State.OPEN);
            if (upload.getUpdatedAt() < upload.getInitiatedAt()) upload.setUpdatedAt(upload.getInitiatedAt());
            MultipartTrees.putUpload(tx, upload);
            MultipartTrees.indexOpen(tx, upload);
            return upload;
        });
    }

    @Override
    public Optional<MultipartUpload> find(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) return Optional.empty();
        return store().read(tx -> MultipartTrees.upload(tx, uploadId));
    }

    // ------------------------------------------------------------------ parts

    @Override
    public PutPartResult putPart(String bucket, String key, ManifestEntity manifest, MultipartPart row) {
        String uploadId = manifest.getMultipartUploadId();
        if (uploadId == null || manifest.getPoolId() == null) {
            throw new IllegalArgumentException("a part manifest needs its upload id and pool");
        }
        manifest.setBucketName(null);      // a hidden manifest is no S3 object
        manifest.setObjectKey(null);
        return store().writeGrouped(tx -> {
            MultipartUpload up = openUpload(tx, uploadId, bucket, key);
            if (!tx.tree(Trees.POOLS).containsKey(Trees.idKey(up.getPoolId()))) {
                throw new ManifestRepository.PoolNotFoundException(up.getPoolId());
            }
            Optional<MultipartPart> previous = MultipartTrees.part(tx, uploadId, row.partNumber());
            manifest.setDeleted(false);
            if (manifest.getStorageKind() == StorageKind.CHUNKED) {
                // references first, retirement of the replaced part after: a chunk both parts share never passes
                // through zero and is never queued for collection
                Chunks.addRefs(tx, manifest.getPoolId(), manifest.chunkKeyArray(), manifest.getStagedChunks());
            }
            Trees.putManifest(tx, manifest);
            MultipartTrees.putPart(tx, uploadId, row);
            Optional<MultipartPart> replaced = Optional.empty();
            if (previous.isPresent() && !previous.get().manifestId().equals(manifest.getId())) {
                Trees.retire(tx, previous.get().manifestId());
                replaced = previous;
            }
            up.setUpdatedAt(Math.max(up.getUpdatedAt(), row.uploadedAt()));
            MultipartTrees.putUpload(tx, up);
            return new PutPartResult(row, replaced);
        });
    }

    @Override
    public PartsPage listParts(String uploadId, String bucket, String key, int afterPartNumber, int maxParts) {
        return store().read(tx -> {
            MultipartUpload up = openUpload(tx, uploadId, bucket, key);
            List<MultipartPart> parts = new ArrayList<>();
            boolean truncated = false;
            byte[] from = MultipartTrees.partKey(uploadId, Math.max(0, afterPartNumber) + 1);
            try (Cursor c = tx.tree(MultipartTrees.PARTS).scan(from, Keys.prefixEnd(Keys.of(uploadId)))) {
                while (c.next()) {
                    if (parts.size() >= maxParts) {
                        truncated = true;
                        break;
                    }
                    parts.add(MultipartTrees.decodePart(c.value()));
                }
            }
            int next = truncated && !parts.isEmpty() ? parts.get(parts.size() - 1).partNumber() : 0;
            return new PartsPage(up, parts, truncated, next);
        });
    }

    // ------------------------------------------------------------------ listing uploads

    @Override
    public UploadListing listUploads(String bucket, String prefix, String delimiter, String keyMarker,
                                     String uploadIdMarker, int maxUploads) {
        if (prefix == null) prefix = "";
        if (keyMarker == null) keyMarker = "";
        boolean grouped = delimiter != null && !delimiter.isEmpty();
        if (maxUploads <= 0) return new UploadListing(List.of(), List.of(), false, null, null);
        final String pfx = prefix;
        final String marker = keyMarker;
        try (ReadTxn tx = store().beginRead()) {
            BTree index = tx.tree(MultipartTrees.BY_BUCKET);
            byte[] bucketPrefix = Keys.builder().string(bucket).stringPrefix(pfx).build();
            byte[] rangeEnd = Keys.prefixEnd(bucketPrefix);
            byte[] from = bucketPrefix;
            String skipUploadIdUpTo = null;       // same key as the marker: skip uploads whose id sorts at or before it
            if (!marker.isEmpty()) {
                byte[] afterMarkerKey = Keys.prefixEnd(Keys.builder().string(bucket).string(marker).build());
                if (uploadIdMarker != null && !uploadIdMarker.isEmpty()) {
                    Optional<MultipartUpload> m = MultipartTrees.upload(tx, uploadIdMarker)
                            .filter(u -> u.getBucket().equals(bucket) && u.getKey().equals(marker));
                    if (m.isPresent()) {
                        byte[] exact = MultipartTrees.byBucketKey(bucket, marker, m.get().getInitiatedAt(), uploadIdMarker);
                        // right after the marker upload: the key bytes plus a zero byte sort directly behind it
                        from = max(from, Arrays.copyOf(exact, exact.length + 1));
                    } else {
                        from = max(from, Keys.builder().string(bucket).string(marker).build());
                        skipUploadIdUpTo = uploadIdMarker;
                    }
                } else {
                    from = max(from, afterMarkerKey == null ? rangeEnd : afterMarkerKey);
                }
                // the marker is itself a common prefix: everything under it was already returned
                if (grouped && marker.startsWith(pfx)) {
                    int d = marker.indexOf(delimiter, pfx.length());
                    if (d >= 0 && d + delimiter.length() == marker.length()) {
                        byte[] past = Keys.prefixEnd(Keys.builder().string(bucket).stringPrefix(marker).build());
                        if (past == null) return new UploadListing(List.of(), List.of(), false, null, null);
                        from = max(from, past);
                    }
                }
            }
            List<MultipartUpload> found = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            boolean truncated = false;
            String lastKey = null;
            String lastUploadId = null;
            int count = 0;
            if (rangeEnd != null && Arrays.compareUnsigned(from, rangeEnd) >= 0) {
                return new UploadListing(found, prefixes, false, null, null);
            }
            Cursor c = index.scan(from, rangeEnd);
            try {
                while (c.next()) {
                    Keys.Reader r = new Keys.Reader(c.key());
                    r.string();                           // bucket
                    String key = r.string();
                    r.longUnsigned();                     // initiatedAt
                    String uploadId = r.string();
                    if (skipUploadIdUpTo != null && key.equals(marker) && uploadId.compareTo(skipUploadIdUpTo) <= 0) continue;
                    if (grouped) {
                        int d = key.indexOf(delimiter, pfx.length());
                        if (d >= 0) {
                            if (count == maxUploads) {
                                truncated = true;
                                break;
                            }
                            String common = key.substring(0, d + delimiter.length());
                            prefixes.add(common);
                            lastKey = common;
                            lastUploadId = null;
                            count++;
                            byte[] next = Keys.prefixEnd(Keys.builder().string(bucket).stringPrefix(common).build());
                            c.close();
                            if (next == null || (rangeEnd != null && Arrays.compareUnsigned(next, rangeEnd) >= 0)) break;
                            c = index.scan(next, rangeEnd);
                            continue;
                        }
                    }
                    if (count == maxUploads) {
                        truncated = true;
                        break;
                    }
                    Optional<MultipartUpload> u = MultipartTrees.upload(tx, uploadId);
                    if (u.isEmpty()) continue;            // cannot happen inside one snapshot; be defensive
                    found.add(u.get());
                    lastKey = key;
                    lastUploadId = uploadId;
                    count++;
                }
            } finally {
                c.close();
            }
            return new UploadListing(found, prefixes, truncated, truncated ? lastKey : null, truncated ? lastUploadId : null);
        }
    }

    private static byte[] max(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b) >= 0 ? a : b;
    }

    @Override
    public boolean hasOpenUploads(String bucket) {
        return store().read(tx -> {
            try (Cursor c = tx.tree(MultipartTrees.BY_BUCKET).scanPrefix(Keys.of(bucket))) {
                return c.next();
            }
        });
    }

    // ------------------------------------------------------------------ complete

    @Override
    public CompleteResult complete(CompleteCommand cmd) {
        if (cmd.parts().isEmpty()) throw new MultipartException(Kind.INVALID_REQUEST, "The part list is empty");
        for (int i = 1; i < cmd.parts().size(); i++) {
            if (cmd.parts().get(i).partNumber() <= cmd.parts().get(i - 1).partNumber()) {
                throw new MultipartException(Kind.INVALID_PART_ORDER,
                        "The list of parts was not in ascending order. The parts list must be specified in order by part number.");
            }
        }
        return store().writeGrouped(tx -> completeInTxn(tx, cmd));
    }

    private CompleteResult completeInTxn(WriteTxn tx, CompleteCommand cmd) {
        String uploadId = cmd.uploadId();
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) throw noSuchUpload(uploadId);
        MultipartUpload up = MultipartTrees.upload(tx, uploadId)
                .filter(u -> u.getBucket().equals(cmd.bucket()) && u.getKey().equals(cmd.key()))
                .orElseThrow(() -> noSuchUpload(uploadId));
        String fingerprint = fingerprint(cmd.parts());
        if (up.getState() == MultipartUpload.State.COMPLETED) {
            if (fingerprint.equals(up.getPartsFingerprint())) {
                return new CompleteResult(up.getBucket(), up.getKey(), up.getResultEtag(), up.getResultManifestId(),
                        up.getChecksumAlgorithm(), up.getResultChecksumValue(), up.getChecksumType(), true, Optional.empty());
            }
            throw noSuchUpload(uploadId);
        }
        if (up.getState() != MultipartUpload.State.OPEN) throw noSuchUpload(uploadId);

        ChecksumAlgorithm uploadAlg = up.getChecksumAlgorithm();
        ChecksumType uploadType = up.getChecksumType();

        // 1. the requested parts: rows, part manifests, ETags and checksums
        List<MultipartPart> rows = new ArrayList<>();
        List<ManifestEntity> partManifests = new ArrayList<>();
        List<byte[]> partDigests = new ArrayList<>();
        List<Long> partLengths = new ArrayList<>();
        List<byte[]> crc32cs = new ArrayList<>();
        byte[] md5Concat = new byte[16 * cmd.parts().size()];
        int at = 0;
        for (RequestedPart rp : cmd.parts()) {
            int pn = rp.partNumber();
            MultipartPart row = MultipartTrees.part(tx, uploadId, pn).orElseThrow(() ->
                    new MultipartException(Kind.INVALID_PART, "Part " + pn + " has not been uploaded"));
            if (rp.etag() != null && !rp.etag().replace("\"", "").equals(row.etag())) {
                throw new MultipartException(Kind.INVALID_PART, "ETag mismatch for part " + pn);
            }
            for (Map.Entry<ChecksumAlgorithm, String> given : rp.checksums().entrySet()) {
                if (row.algorithm() != given.getKey()) {
                    throw new MultipartException(Kind.INVALID_PART,
                            "Part " + pn + " was not uploaded with a " + given.getKey().name() + " checksum");
                }
                if (!row.checksum().equals(given.getValue())) {
                    throw new MultipartException(Kind.BAD_DIGEST, "The " + given.getKey().name()
                            + " you specified for part " + pn + " did not match what we received.");
                }
            }
            if (uploadAlg != null) {
                if (row.algorithm() != uploadAlg) {
                    throw new MultipartException(Kind.INVALID_PART, "Part " + pn + " has no " + uploadAlg.name() + " checksum");
                }
                partDigests.add(uploadAlg.decode(row.checksum()));
            }
            ManifestEntity pm = Trees.manifest(tx, row.manifestId()).filter(m -> !m.isDeleted()).orElseThrow(() ->
                    new MultipartException(Kind.INVALID_PART, "Part " + pn + " has no stored data"));
            rows.add(row);
            partManifests.add(pm);
            partLengths.add(row.size());
            crc32cs.add(pm.getCrc32c() == null ? null : Base64.getDecoder().decode(pm.getCrc32c()));
            System.arraycopy(HexFormat.of().parseHex(row.etag()), 0, md5Concat, at, 16);
            at += 16;
        }

        // 2. the checksums of the finished object, from the stored part values (no data is read)
        ChecksumAlgorithm finalAlg = null;
        String finalValue = null;
        ChecksumType finalType = null;
        if (uploadAlg != null) {
            finalAlg = uploadAlg;
            if (uploadType == ChecksumType.COMPOSITE) {
                finalValue = uploadAlg.composite(partDigests);
                finalType = ChecksumType.COMPOSITE;
            } else {
                if (!CrcCombine.supports(uploadAlg)) {
                    throw new MultipartException(Kind.INVALID_REQUEST, uploadAlg.name() + " cannot describe a whole object");
                }
                finalValue = uploadAlg.encode(CrcCombine.combineAll(uploadAlg, partDigests, partLengths));
                finalType = ChecksumType.FULL_OBJECT;
            }
        }
        for (Map.Entry<ChecksumAlgorithm, String> header : cmd.wholeObjectHeaders().entrySet()) {
            ChecksumAlgorithm a = header.getKey();
            if (a != uploadAlg) {
                throw new MultipartException(Kind.INVALID_REQUEST,
                        "The upload was not created with the " + a.name() + " checksum algorithm.");
            }
            String given = header.getValue().trim();
            if (finalType == ChecksumType.FULL_OBJECT) {
                try {
                    given = a.encode(a.decode(given));
                } catch (IllegalArgumentException e) {
                    throw new MultipartException(Kind.INVALID_REQUEST, "Value for " + a.headerName() + " header is invalid.");
                }
            }
            if (!finalValue.equals(given)) {
                throw new MultipartException(Kind.BAD_DIGEST,
                        "The " + a.name() + " you specified did not match the calculated checksum.");
            }
        }
        String crc32c = null;
        if (crc32cs.stream().noneMatch(java.util.Objects::isNull)) {
            int acc = ByteBuffer.wrap(crc32cs.get(0)).getInt();
            for (int i = 1; i < crc32cs.size(); i++) {
                acc = CrcCombine.crc32c(acc, ByteBuffer.wrap(crc32cs.get(i)).getInt(), partLengths.get(i));
            }
            crc32c = ChecksumAlgorithm.CRC32C.encode(ChecksumAlgorithm.crc32Bytes(acc));
        }
        String etag = "\"" + HexFormat.of().formatHex(md5().digest(md5Concat)) + "-" + cmd.parts().size() + "\"";

        // 3. the object manifest: the parts concatenated; their chunk keys and small records, no data
        ManifestEntity object = new ManifestEntity();
        object.setId(UUID.randomUUID().toString());
        object.setStorageKind(StorageKind.MULTIPART);
        object.setPoolId(up.getPoolId());
        object.setBucketName(up.getBucket());
        object.setObjectKey(up.getKey());
        object.setSourceFileName(up.getKey());
        object.setCreatedAt(Instant.ofEpochMilli(cmd.now()));
        object.setContentType(up.getContentType() != null ? up.getContentType() : "application/octet-stream");
        object.setCacheControl(up.getCacheControl());
        object.setContentDisposition(up.getContentDisposition());
        object.setContentEncoding(up.getContentEncoding());
        object.setContentLanguage(up.getContentLanguage());
        if (up.getUserMetadata() != null && !up.getUserMetadata().isEmpty()) object.setUserMetadata(up.getUserMetadata());
        object.setAcl(up.getAcl());
        object.setEtag(etag);
        object.setCrc32c(crc32c);
        object.setChecksumAlgorithm(finalAlg);
        object.setChecksumValue(finalValue);
        object.setChecksumType(finalType);
        List<PartSegment> segments = new ArrayList<>();
        long[][] keyLists = new long[partManifests.size()][];
        int totalKeys = 0;
        long totalBytes = 0;
        for (int i = 0; i < partManifests.size(); i++) {
            ManifestEntity pm = partManifests.get(i);
            int pn = rows.get(i).partNumber();
            switch (pm.getStorageKind()) {
                case CHUNKED -> {
                    keyLists[i] = pm.chunkKeyArray();
                    totalKeys += keyLists[i].length;
                    segments.add(PartSegment.chunked(pn, pm.getTotalBytes(), pm.getChunkSize(), pm.getLastChunkSize(),
                            keyLists[i].length));
                }
                case SMALL -> segments.add(PartSegment.small(pn, pm.getSmallBlobId(), pm.getSmallOffset(),
                        pm.getSmallLength(), pm.getSmallCrc32c()));
                case EMPTY -> segments.add(PartSegment.empty(pn));
                default -> throw new MultipartException(Kind.INVALID_PART, "Part " + pn + " has an unsupported layout");
            }
            totalBytes += pm.getTotalBytes();
        }
        long[] keys = new long[totalKeys];
        int pos = 0;
        for (long[] k : keyLists) {
            if (k == null) continue;
            System.arraycopy(k, 0, keys, pos, k.length);
            pos += k.length;
        }
        object.setSegments(segments);
        object.setChunkKeyArray(keys);
        object.setTotalChunks(keys.length);
        object.setTotalBytes(totalBytes);

        // 4. the references of the part manifests become the references of the object: the part records disappear
        //    WITHOUT releasing anything (a tombstone would let the collector mark the small records DELETED)
        for (ManifestEntity pm : partManifests) Trees.deleteManifestRecord(tx, pm);
        // parts that were uploaded but not listed are discarded
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (MultipartPart row : rows) listed.add(row.manifestId());
        for (MultipartPart extra : MultipartTrees.parts(tx, uploadId)) {
            if (!listed.contains(extra.manifestId())) Trees.retire(tx, extra.manifestId());
        }
        MultipartTrees.deleteParts(tx, uploadId);

        ManifestRepository.CommitResult committed =
                MetaStoreManifestRepository.commitInTxn(tx, object, cmd.precondition());

        // 5. the upload keeps its answer for a retried Complete
        MultipartTrees.unindex(tx, up);
        up.setState(MultipartUpload.State.COMPLETED);
        up.setCompletedAt(cmd.now());
        up.setUpdatedAt(cmd.now());
        up.setResultEtag(etag);
        up.setResultManifestId(object.getId());
        up.setResultChecksumValue(finalValue);
        up.setChecksumAlgorithm(finalAlg);
        up.setChecksumType(finalType);
        up.setPartsFingerprint(fingerprint);
        MultipartTrees.putUpload(tx, up);
        return new CompleteResult(up.getBucket(), up.getKey(), etag, object.getId(), finalAlg, finalValue, finalType, false,
                committed.superseded());
    }

    private static String fingerprint(List<RequestedPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (RequestedPart p : parts) {
            sb.append(p.partNumber()).append(':').append(p.etag() == null ? "" : p.etag().replace("\"", "")).append(';');
        }
        return HexFormat.of().formatHex(md5().digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ abort / expiry

    @Override
    public int abort(String uploadId, String bucket, String key, long now) {
        return store().writeGrouped(tx -> {
            MultipartUpload up = openUpload(tx, uploadId, bucket, key);
            return discard(tx, up, now);
        });
    }

    /** Retires every part, drops the rows and marks the upload ABORTED. Returns the number of parts retired. */
    private static int discard(WriteTxn tx, MultipartUpload up, long now) {
        int n = 0;
        for (MultipartPart p : MultipartTrees.parts(tx, up.getUploadId())) {
            if (Trees.retire(tx, p.manifestId()).isPresent()) n++;
        }
        MultipartTrees.deleteParts(tx, up.getUploadId());
        MultipartTrees.unindex(tx, up);
        up.setState(MultipartUpload.State.ABORTED);
        up.setAbortedAt(now);
        up.setUpdatedAt(now);
        MultipartTrees.putUpload(tx, up);
        return n;
    }

    @Override
    public CleanupReport cleanup(long now, long staleAfterMillis, long retentionMillis) {
        List<MultipartUpload> all = store().read(MultipartTrees::allUploads);
        int expired = 0, parts = 0, removed = 0, failed = 0;
        for (MultipartUpload snapshot : all) {
            try {
                switch (snapshot.getState()) {
                    case OPEN -> {
                        if (now - Math.max(snapshot.getUpdatedAt(), snapshot.getInitiatedAt()) <= staleAfterMillis) continue;
                        Integer retired = store().writeGrouped(tx -> {
                            // re-read: a part or a Complete may have arrived since the scan
                            MultipartUpload up = MultipartTrees.upload(tx, snapshot.getUploadId()).orElse(null);
                            if (up == null || up.getState() != MultipartUpload.State.OPEN
                                    || now - Math.max(up.getUpdatedAt(), up.getInitiatedAt()) <= staleAfterMillis) return null;
                            return discard(tx, up, now);
                        });
                        if (retired != null) {
                            expired++;
                            parts += retired;
                            log.info("Expired multipart upload {} ({}/{}): {} part(s) released", snapshot.getUploadId(),
                                    snapshot.getBucket(), snapshot.getKey(), retired);
                        }
                    }
                    case COMPLETED, ABORTED -> {
                        long since = snapshot.getState() == MultipartUpload.State.COMPLETED
                                ? snapshot.getCompletedAt() : snapshot.getAbortedAt();
                        if (now - since <= retentionMillis) continue;
                        store().writeGroupedVoid(tx -> tx.tree(MultipartTrees.UPLOADS)
                                .delete(MultipartTrees.uploadKey(snapshot.getUploadId())));
                        removed++;
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Skipping multipart upload {} during cleanup: {}", snapshot.getUploadId(), e.toString());
            }
        }
        return new CleanupReport(expired, parts, removed, failed);
    }

    @Override
    public Map<MultipartUpload.State, Long> countByState() {
        return store().read(tx -> {
            Map<MultipartUpload.State, Long> out = new EnumMap<>(MultipartUpload.State.class);
            for (MultipartUpload.State s : MultipartUpload.State.values()) out.put(s, 0L);
            for (MultipartUpload u : MultipartTrees.allUploads(tx)) out.merge(u.getState(), 1L, Long::sum);
            return out;
        });
    }
}
