package org.example.entities;

public enum CuckooTable {
    A(0), B(1);

    private final int id;

    CuckooTable(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static CuckooTable fromId(int id) {
        return id == 0 ? A : B;
    }
}
