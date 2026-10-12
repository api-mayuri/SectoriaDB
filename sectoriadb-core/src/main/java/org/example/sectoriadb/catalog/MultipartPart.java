package org.example.sectoriadb.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.sectoriadb.checksum.ChecksumAlgorithm;

/**
 * One uploaded part of an open upload: a row of {@code mpu_parts}. The bytes belong to the hidden part manifest
 * {@code manifestId}; the row carries what ListParts and Complete need without reading that manifest.
 *
 * @param etag      hex MD5 of the part, without quotes
 * @param crc32c    base64 CRC32C of the part (always computed; combined into the CRC32C of the finished object)
 * @param algorithm additional checksum of the part (the one the upload was created with, or the client's), or null
 * @param checksum  base64 value of {@code algorithm}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MultipartPart(int partNumber, String manifestId, long size, String etag, String crc32c,
                            ChecksumAlgorithm algorithm, String checksum, long uploadedAt) {
}
