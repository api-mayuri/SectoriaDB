package org.example.sectoriadb.repository.metastore;

import org.example.sectoriadb.model.PoolEntity;

/**
 * Value of the {@code pools} tree: {@code u8 formatVersion | JSON}. The whole record is protected by the page CRC
 * and replaced atomically by a metastore commit, so a readable JSON body is all that is needed.
 */
public final class PoolCodec {

    static final int VERSION = 1;

    private PoolCodec() {
    }

    public static byte[] encode(PoolEntity pool) {
        byte[] json = JsonValues.write(pool);
        byte[] out = new byte[1 + json.length];
        out[0] = VERSION;
        System.arraycopy(json, 0, out, 1, json.length);
        return out;
    }

    public static PoolEntity decode(byte[] value) {
        if (value.length < 2 || value[0] != VERSION) {
            throw new IllegalStateException("unsupported pool record version "
                    + (value.length == 0 ? "(empty)" : String.valueOf(value[0])));
        }
        return JsonValues.read(value, 1, value.length - 1, PoolEntity.class);
    }
}
