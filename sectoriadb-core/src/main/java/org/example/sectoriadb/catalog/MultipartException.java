package org.example.sectoriadb.catalog;

/** A multipart request that cannot be honoured; {@link Kind} maps one to one to an S3 error code. */
public class MultipartException extends RuntimeException {

    public enum Kind {
        /** NoSuchUpload: unknown id, wrong bucket / key, or no longer open */
        NO_SUCH_UPLOAD,
        /** InvalidPart: a listed part is missing, its ETag or checksum differs */
        INVALID_PART,
        /** InvalidPartOrder */
        INVALID_PART_ORDER,
        /** EntityTooSmall */
        ENTITY_TOO_SMALL,
        /** InvalidRequest */
        INVALID_REQUEST,
        /** BadDigest: a checksum given for a part or for the whole upload does not match */
        BAD_DIGEST
    }

    private final Kind kind;

    public MultipartException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
