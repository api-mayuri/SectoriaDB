package org.example.s3;

import org.example.entity.PoolEntity;
import org.example.s3.xml.BucketEntry;
import org.example.s3.xml.ListAllMyBucketsResult;
import org.example.s3.xml.Owner;
import org.example.service.PoolService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * S3 service-level operations.
 *
 * GET / — ListBuckets
 */
@RestController
public class S3RootController {

    private static final Owner OWNER = new Owner("sectoriadb", "sectoriadb");

    private final PoolService poolService;

    public S3RootController(PoolService poolService) {
        this.poolService = poolService;
    }

    @GetMapping(value = "/", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<ListAllMyBucketsResult> listBuckets() {
        List<BucketEntry> buckets = poolService.listAll().stream()
                .map(p -> new BucketEntry(p.getName(), S3Support.isoDate(p.getCreatedAt())))
                .toList();

        return ResponseEntity.ok()
                .header("x-amz-request-id", S3Support.requestId())
                .header("Server", "SectoriaDB")
                .body(new ListAllMyBucketsResult(OWNER, buckets));
    }
}
