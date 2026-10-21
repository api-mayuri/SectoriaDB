package org.example.sectoriadb.engine.blob;

public enum SlotStateMap {
    FREE(0),
    ACTIVE(1),
    DELETED(2),
    RESERVED(3),
    /** In-memory only: the on-disk meta entry failed its CRC. Never written to disk, never reused. */
    QUARANTINED(4);

    private final byte value;

    SlotStateMap(int value) {
        this.value = (byte) value;
    }

    public byte getValue() {
        return value;
    }
}
