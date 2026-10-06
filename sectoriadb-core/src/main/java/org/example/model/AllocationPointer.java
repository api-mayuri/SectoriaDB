package org.example.model;

/**
 * Результат аллокации места. Указывает, КУДА именно записывать чанк.
 * @param targetBlobFile Файл, в который нужно писать
 * @param offset Абсолютный оффсет (сдвиг) от начала этого файла в байтах
 */
public record AllocationPointer(
        BlobFile targetBlobFile,
        long offset
) {}
