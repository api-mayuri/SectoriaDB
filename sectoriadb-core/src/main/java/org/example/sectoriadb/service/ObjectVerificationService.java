package org.example.sectoriadb.service;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.checksum.ChecksumType;
import org.example.sectoriadb.checksum.MultiDigest;
import org.example.sectoriadb.checksum.MultiDigestOutputStream;
import org.example.sectoriadb.model.ManifestEntity;
import org.example.sectoriadb.service.impl.ChunkCorruptedException;
import org.example.sectoriadb.service.impl.ChunkNotFoundException;
import org.example.sectoriadb.service.impl.SmallObjectCorruptedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

/**
 * Re-reads stored objects and checks every integrity layer that can be checked offline: per-chunk / per-record
 * CRC32C (done by the read path itself), the stored whole-object CRC32C, the client's additional checksum (full-object
 * only; composite values cannot be recomputed without the part boundaries) and the MD5 of non-multipart ETags.
 */
@Service
public class ObjectVerificationService {

    public enum Status { OK, CORRUPT, MISSING }

    /** @param details one line per check that ran (or was skipped), in order */
    public record Result(String manifestId, String name, Status status, List<String> details) {
        public boolean ok() { return status == Status.OK; }
    }

    public record Summary(int total, int ok, int corrupt, int missing, List<Result> problems) {
        public boolean allOk() { return corrupt == 0 && missing == 0; }
    }

    private final FileStorageService fileService;

    public ObjectVerificationService(FileStorageService fileService) {
        this.fileService = fileService;
    }

    public Result verify(ManifestEntity m) {
        String name = m.getBucketName() != null && m.getObjectKey() != null
                ? m.getBucketName() + "/" + m.getObjectKey() : m.getSourceFileName();
        List<String> details = new ArrayList<>();

        String etag = m.getEtag() == null ? "" : m.getEtag().replace("\"", "");
        boolean checkMd5 = !etag.isEmpty() && !etag.contains("-");
        ChecksumAlgorithm clientAlg = m.getChecksumAlgorithm() != null && m.getChecksumType() == ChecksumType.FULL_OBJECT
                && m.getChecksumValue() != null ? m.getChecksumAlgorithm() : null;
        EnumSet<ChecksumAlgorithm> algs = EnumSet.of(ChecksumAlgorithm.CRC32C);
        if (clientAlg != null) algs.add(clientAlg);
        MultiDigest digest = new MultiDigest(checkMd5, algs);

        try (OutputStream out = new MultiDigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            fileService.streamToOutput(m, out, false);   // chunk / record CRCs are checked by the read path
        } catch (ChunkCorruptedException | SmallObjectCorruptedException e) {
            details.add("CORRUPT: " + e.getMessage());
            return new Result(m.getId(), name, Status.CORRUPT, details);
        } catch (ChunkNotFoundException e) {
            details.add("MISSING: " + e.getMessage());
            return new Result(m.getId(), name, Status.MISSING, details);
        } catch (IOException | RuntimeException e) {
            // e.g. the blob file is gone
            details.add("MISSING: " + e.getMessage());
            return new Result(m.getId(), name, Status.MISSING, details);
        }
        digest.finish();
        details.add("chunk/record CRC32C: ok");

        boolean corrupt = false;
        String actualCrc = digest.encoded(ChecksumAlgorithm.CRC32C);
        if (m.getCrc32c() == null) {
            details.add("whole-object CRC32C: not stored (manifest predates it)");
        } else if (m.getCrc32c().equals(actualCrc)) {
            details.add("whole-object CRC32C: ok (" + actualCrc + ")");
        } else {
            details.add("MISMATCH whole-object CRC32C: stored " + m.getCrc32c() + ", computed " + actualCrc);
            corrupt = true;
        }

        if (clientAlg != null) {
            String actual = digest.encoded(clientAlg);
            if (m.getChecksumValue().equals(actual)) {
                details.add("client checksum " + clientAlg + ": ok");
            } else {
                details.add("MISMATCH client checksum " + clientAlg + ": stored " + m.getChecksumValue()
                        + ", computed " + actual);
                corrupt = true;
            }
        } else if (m.getChecksumAlgorithm() != null) {
            details.add("client checksum " + m.getChecksumAlgorithm() + " (" + m.getChecksumType()
                    + "): not recomputable, skipped");
        }

        if (checkMd5) {
            String actual = HexFormat.of().formatHex(digest.md5());
            if (actual.equalsIgnoreCase(etag)) {
                details.add("MD5 vs ETag: ok");
            } else {
                details.add("MISMATCH MD5: computed " + actual + ", stored ETag " + etag);
                corrupt = true;
            }
        } else {
            details.add("MD5 vs ETag: not applicable (multipart or no ETag)");
        }
        return new Result(m.getId(), name, corrupt ? Status.CORRUPT : Status.OK, details);
    }

    /** Verifies every live object accepted by {@code filter}. */
    public Summary verifyAll(Predicate<ManifestEntity> filter) {
        int total = 0, ok = 0, corrupt = 0, missing = 0;
        List<Result> problems = new ArrayList<>();
        for (ManifestEntity m : fileService.listAll()) {
            if (!filter.test(m)) continue;
            total++;
            Result r = verify(m);
            switch (r.status()) {
                case OK -> ok++;
                case CORRUPT -> { corrupt++; problems.add(r); }
                case MISSING -> { missing++; problems.add(r); }
            }
        }
        return new Summary(total, ok, corrupt, missing, problems);
    }
}
