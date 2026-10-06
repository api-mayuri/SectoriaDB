package org.example.model;

import java.nio.file.Path;

/**
 * Модель, представляющая конкретный файл данных .raw внутри пула.
 * @param id Уникальный ID файла (например, UUID или порядковый номер)
 * @param path Полный физический путь к файлу на диске
 * @param size Текущий размер файла в байтах
 */
public record BlobFile(
        String id,
        Path path,
        long size
) {}