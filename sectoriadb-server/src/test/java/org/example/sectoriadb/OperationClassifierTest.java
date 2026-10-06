package org.example.sectoriadb;

import org.example.sectoriadb.observability.OperationClassifier;
import org.example.sectoriadb.observability.S3Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The (method, path, query, x-amz-copy-source) -> operation table: one row per operation the controllers serve. */
class OperationClassifierTest {

    private static S3Operation op(String method, String path, String query, boolean copySource) {
        return OperationClassifier.classify(method, path, query, copySource);
    }

    /** method | path | query | copy-source header | expected operation. '-' stands for "none". */
    @ParameterizedTest(name = "{0} {1}?{2} copy={3} -> {4}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // root
            "GET     | /                    | -                         | false | ListBuckets",
            // bucket level
            "PUT     | /b                   | -                         | false | CreateBucket",
            "PUT     | /b/                  | -                         | false | CreateBucket",
            "HEAD    | /b                   | -                         | false | HeadBucket",
            "DELETE  | /b                   | -                         | false | DeleteBucket",
            "GET     | /b                   | -                         | false | ListObjects",
            "GET     | /b/                  | prefix=a&delimiter=%2F    | false | ListObjects",
            "GET     | /b                   | list-type=2               | false | ListObjectsV2",
            "GET     | /b                   | list-type=2&prefix=x      | false | ListObjectsV2",
            "GET     | /b                   | list-type=1               | false | ListObjects",
            "GET     | /b                   | uploads                   | false | ListMultipartUploads",
            "GET     | /b                   | uploads=                  | false | ListMultipartUploads",
            "POST    | /b                   | delete                    | false | DeleteObjects",
            "GET     | /b                   | location                  | false | GetBucketLocation",
            "GET     | /b                   | versioning                | false | GetBucketVersioning",
            "GET     | /b                   | acl                       | false | GetBucketAcl",
            "PUT     | /b                   | acl                       | false | PutBucketAcl",
            "GET     | /b                   | policy                    | false | GetBucketPolicy",
            "PUT     | /b                   | policy                    | false | PutBucketPolicy",
            "DELETE  | /b                   | policy                    | false | DeleteBucketPolicy",
            "GET     | /b                   | tagging                   | false | GetBucketTagging",
            "GET     | /b                   | cors                      | false | GetBucketCors",
            "GET     | /b                   | lifecycle                 | false | GetBucketLifecycle",
            "GET     | /b                   | encryption                | false | GetBucketEncryption",
            "GET     | /b                   | publicAccessBlock         | false | GetPublicAccessBlock",
            // objects
            "PUT     | /b/k                 | -                         | false | PutObject",
            "PUT     | /b/dir/sub/k.txt     | -                         | false | PutObject",
            "PUT     | /b/k                 | -                         | true  | CopyObject",
            "GET     | /b/k                 | -                         | false | GetObject",
            "GET     | /b/k                 | response-content-type=a   | false | GetObject",
            "HEAD    | /b/k                 | -                         | false | HeadObject",
            "DELETE  | /b/k                 | -                         | false | DeleteObject",
            "GET     | /b/k                 | acl                       | false | GetObjectAcl",
            "PUT     | /b/k                 | acl                       | false | PutObjectAcl",
            "GET     | /b/k                 | tagging                   | false | GetObjectTagging",
            "PUT     | /b/k                 | tagging                   | false | PutObjectTagging",
            "DELETE  | /b/k                 | tagging                   | false | DeleteObjectTagging",
            // multipart
            "POST    | /b/k                 | uploads                   | false | CreateMultipartUpload",
            "PUT     | /b/k                 | partNumber=1&uploadId=u   | false | UploadPart",
            "PUT     | /b/k                 | uploadId=u&partNumber=2   | true  | UploadPartCopy",
            "POST    | /b/k                 | uploadId=u                | false | CompleteMultipartUpload",
            "DELETE  | /b/k                 | uploadId=u                | false | AbortMultipartUpload",
            "GET     | /b/k                 | uploadId=u                | false | ListParts",
            // everything else
            "POST    | /b/k                 | -                         | false | Other",
            "POST    | /b                   | -                         | false | Other",
            "POST    | /                    | -                         | false | Other",
            "PUT     | /                    | -                         | false | Other",
            "OPTIONS | /b/k                 | -                         | false | Other",
            "PATCH   | /b                   | -                         | false | Other",
            "PUT     | /b                   | tagging                   | false | Other",
            "DELETE  | /b                   | cors                      | false | Other",
    })
    void classificationTable(String method, String path, String query, boolean copy, S3Operation expected) {
        assertEquals(expected, op(method, path, query, copy));
    }

    @Test
    void everyOperationIsReachableFromSomeRequest() {
        // Guards against a constant that no rule can produce (dead label) or a forgotten new constant.
        Set<S3Operation> seen = EnumSet.noneOf(S3Operation.class);
        String[] methods = {"GET", "PUT", "POST", "DELETE", "HEAD", "OPTIONS"};
        String[] paths = {"/", "/b", "/b/", "/b/k"};
        String[] queries = {null, "acl", "policy", "location", "versioning", "tagging", "cors", "lifecycle", "encryption",
                "publicAccessBlock", "uploads", "delete", "uploadId=u", "partNumber=1&uploadId=u", "list-type=2"};
        for (String m : methods) for (String p : paths) for (String q : queries) for (boolean c : new boolean[]{false, true}) {
            seen.add(op(m, p, q, c));
        }
        assertEquals(EnumSet.allOf(S3Operation.class), seen);
    }

    @Test
    void keysNeverInfluenceTheOperationOrLeakIntoIt() {
        assertEquals(S3Operation.GetObject, op("GET", "/bucket/acl", null, false));
        assertEquals(S3Operation.PutObject, op("PUT", "/bucket/uploads/uploadId", null, false));
        assertEquals(S3Operation.GetObject, op("GET", "/bucket//weird//key", null, false));
        assertEquals("GetObject", S3Operation.GetObject.label());
    }

    @Test
    void nullsAndGarbageAreOther() {
        assertEquals(S3Operation.Other, op(null, "/b", null, false));
        assertEquals(S3Operation.Other, op("GET", null, null, false));
        assertEquals(S3Operation.GetObject, op("get", "/b/k", "&&=&", false));
    }
}
