package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.s3.error.S3Exception;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * The part list of a CompleteMultipartUpload request.
 *
 * @param partNumbers in the order the client listed them (never sorted: S3 answers InvalidPartOrder instead)
 * @param etags       ETags given per part number
 * @param checksums   per part: checksum values given in the XML (ChecksumCRC32C, ...)
 */
record ParsedParts(List<Integer> partNumbers, Map<Integer, String> etags,
                   Map<Integer, Map<ChecksumAlgorithm, String>> checksums) {

    static final int MAX_PARTS = 10000;

    /** Rejects an empty list, more than 10000 parts and a list that is not in ascending order. */
    void requireValid() {
        if (partNumbers.isEmpty()) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "Request body is not valid XML or contains no parts");
        }
        if (partNumbers.size() > MAX_PARTS) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", "A multipart upload has at most 10000 parts");
        }
        for (int i = 1; i < partNumbers.size(); i++) {
            if (partNumbers.get(i) <= partNumbers.get(i - 1)) {
                throw new S3Exception(HttpStatus.BAD_REQUEST, "InvalidPartOrder",
                        "The list of parts was not in ascending order. The parts list must be specified in order by part number.");
            }
        }
    }
}
