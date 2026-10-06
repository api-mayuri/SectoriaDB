package org.example.entities;

import java.nio.file.Path;

/**
 * Абстракция физического диска или точки монтирования на хосте.
 * @param id Уникальный идентификатор диска (например, "nvme0" или "hdd1")
 * @param mountPoint Путь в файловой системе (например, Path.of("/mnt/storage"))
 * @param totalSpace Общий объем диска в байтах
 */
public record StorageDevice(
        String id,
        Path mountPoint,
        long totalSpace
) {}

