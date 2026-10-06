package org.example.model;

import java.util.List;

/**
 * Логический пул, объединяющий группу файлов данных на конкретном устройстве.
 * @param name Уникальное имя пула (например, "fast-nvme-pool")
 * @param device Устройство, на котором физически расположен пул
 * @param blobFiles Список файлов, входящих в данный пул
 */
public record StoragePool(
        String name,
        StorageDevice device,
        List<BlobFile> blobFiles
) {}