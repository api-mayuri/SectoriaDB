package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.BlobFileEntity;

/** Value of the {@code blobs} tree: {@code u8 formatVersion | JSON}. The resolved pool reference is not stored. */
public final class BlobCodec {

    static final int VERSION = 1;

    private BlobCodec() {
    }

    public static byte[] encode(BlobFileEntity blob) {
        byte[] json = JsonValues.write(blob);
        byte[] out = new byte[1 + json.length];
        out[0] = VERSION;
        System.arraycopy(json, 0, out, 1, json.length);
        return out;
    }

    public static BlobFileEntity decode(byte[] value) {
        if (value.length < 2 || value[0] != VERSION) {
            throw new IllegalStateException("unsupported blob record version "
                    + (value.length == 0 ? "(empty)" : String.valueOf(value[0])));
        }
        return JsonValues.read(value, 1, value.length - 1, BlobFileEntity.class);
    }
}
