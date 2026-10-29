package org.example.sectoriadb.s3.multipart;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.example.sectoriadb.s3.error.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The CompleteMultipartUpload body from an untrusted client: whatever the bytes, the parser answers an S3 error
 * ({@link S3Exception}: MalformedXML, InvalidArgument) or the parts; no other exception reaches the request thread.
 * Half of the inputs start from a valid document so the fuzzer reaches the part/ETag/checksum handling.
 */
class CompleteXmlFuzzTest {

    private static final String VALID = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\"a\"</ETag>"
            + "<ChecksumCRC32C>AAAAAA==</ChecksumCRC32C></Part><Part><PartNumber>2</PartNumber><ETag>b</ETag></Part>"
            + "</CompleteMultipartUpload>";

    @FuzzTest(maxDuration = "2m")
    void completeBody(FuzzedDataProvider in) {
        byte[] body;
        if (in.consumeBoolean()) {
            String s = VALID;
            int edits = in.consumeInt(0, 8);
            for (int i = 0; i < edits && !s.isEmpty(); i++) {
                int at = in.consumeInt(0, s.length() - 1);
                s = s.substring(0, at) + in.consumeString(12) + s.substring(Math.min(s.length(), at + in.consumeInt(0, 6)));
            }
            body = s.getBytes(StandardCharsets.UTF_8);
        } else {
            body = in.consumeRemainingAsBytes();
        }
        try {
            CompleteRequestParser.parse(new ByteArrayInputStream(body));
        } catch (S3Exception expected) {
            // the contract: a client error
        }
    }
}
