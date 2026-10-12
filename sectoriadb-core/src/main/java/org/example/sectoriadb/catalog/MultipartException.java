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
    private long proposedSize = -1;
    private long minSizeAllowed = -1;
    private int partNumber;

    public MultipartException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /** EntityTooSmall: the part {@code partNumber} has {@code proposedSize} bytes, fewer than {@code minSizeAllowed}. */
    public static MultipartException entityTooSmall(int partNumber, long proposedSize, long minSizeAllowed) {
        MultipartException e = new MultipartException(Kind.ENTITY_TOO_SMALL, "Your proposed upload is smaller than the minimum"
                + " allowed size: part " + partNumber + " has " + proposedSize + " bytes, the minimum is " + minSizeAllowed);
        e.proposedSize = proposedSize;
        e.minSizeAllowed = minSizeAllowed;
        e.partNumber = partNumber;
        return e;
    }

    public long proposedSize() { return proposedSize; }
    public long minSizeAllowed() { return minSizeAllowed; }
    public int partNumber() { return partNumber; }

    public Kind kind() {
        return kind;
    }
}
