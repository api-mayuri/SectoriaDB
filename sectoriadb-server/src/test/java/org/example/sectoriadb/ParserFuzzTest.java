package org.example.sectoriadb;

import org.example.sectoriadb.s3.auth.AwsChunkedInputStream;
import org.example.sectoriadb.s3.auth.ChunkSigningContext;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.http.ByteRange;
import org.example.sectoriadb.s3.http.CopySource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property-style fuzzing (seeded, time-bounded) of the pure parsers that face untrusted input: Range, copy source,
 * copy range and the aws-chunked decoder. A parser may only succeed or fail with its documented exception
 * ({@link S3Exception} / {@link IOException}); any other exception type, a hang or an unbounded read is a bug.
 */
class ParserFuzzTest {

    private static final long BUDGET_MS = 1_500;

    @Test
    void rangeHeadersEitherParseWithinTheObjectOrFailWithAnS3Error() {
        Random rnd = new Random(FuzzSupport.SEED);
        long[] sizes = {0, 1, 100, Integer.MAX_VALUE, Long.MAX_VALUE};
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        while (System.nanoTime() < end) {
            String header = rnd.nextBoolean()
                    ? FuzzSupport.mutate("bytes=" + rnd.nextInt(200) + "-" + rnd.nextInt(200), rnd)
                    : (rnd.nextBoolean() ? "bytes=" : "") + FuzzSupport.tokens(rnd, 6);
            long total = sizes[rnd.nextInt(sizes.length)];
            for (boolean copy : new boolean[]{false, true}) {
                try {
                    ByteRange r = copy ? ByteRange.parseCopyRange(header, total) : ByteRange.parseRequestRange(header, total);
                    if (r != null) {
                        assertTrue(r.first() >= 0 && r.first() <= r.last() && r.last() < total, "range outside the object: " + header + " / " + total + " -> " + r);
                    }
                } catch (S3Exception expected) {
                    // documented: InvalidRange / InvalidArgument
                } catch (Throwable t) {
                    fail("Range header '" + header + "' (total " + total + ", copy=" + copy + ") -> " + t, t);
                }
            }
            runs++;
        }
        assertTrue(runs > 1000, "too few iterations: " + runs);
    }

    @Test
    void copySourceEitherParsesOrFailsWithAnS3Error() {
        Random rnd = new Random(FuzzSupport.SEED + 1);
        String[] seeds = {"/bucket/key", "bucket/dir/key%20name", "/bucket/key?versionId=abc", "/b/%E2%82%AC"};
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        while (System.nanoTime() < end) {
            String header = rnd.nextInt(3) == 0 ? FuzzSupport.tokens(rnd, 8) : FuzzSupport.mutate(seeds[rnd.nextInt(seeds.length)], rnd);
            try {
                CopySource s = CopySource.parse(header);
                assertTrue(s.bucket() != null && s.key() != null);
            } catch (S3Exception expected) {
                // documented: InvalidArgument
            } catch (Throwable t) {
                fail("x-amz-copy-source '" + header + "' -> " + t, t);
            }
            runs++;
        }
        assertTrue(runs > 1000, "too few iterations: " + runs);
    }

    @Test
    void awsChunkedBodiesEitherDecodeOrFailWithAnIoException() throws Exception {
        Random rnd = new Random(FuzzSupport.SEED + 2);
        byte[][] seeds = {
            "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
            "3;chunk-signature=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\r\nabc\r\n0;chunk-signature=00\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
            "4\r\nabcd\r\n3\r\nxyz\r\n0\r\nx-amz-checksum-crc32:AAAAAA==\r\nx-amz-trailer-signature:ff\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
        };
        ChunkSigningContext signing = new ChunkSigningContext(new byte[32], "20240101T000000Z", "20240101/us-east-1/s3/aws4_request", "ab".repeat(32), true);
        long end = System.nanoTime() + BUDGET_MS * 1_000_000;
        int runs = 0;
        long decoded = 0;
        while (System.nanoTime() < end) {
            byte[] body = rnd.nextInt(5) == 0 ? randomBytes(rnd, 64) : FuzzSupport.mutate(seeds[rnd.nextInt(seeds.length)], rnd);
            ChunkSigningContext ctx = rnd.nextBoolean() ? signing : null;
            long declared = rnd.nextInt(3) == 0 ? rnd.nextInt(20) - 1 : -1;
            try (AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body), ctx, trailers -> { }, declared)) {
                decoded += in.readAllBytes().length;   // the declared sizes are not bigger than the input: memory stays bounded by the input
            } catch (IOException expected) {
                // PayloadVerificationException (malformed chunks / bad signature) or end of stream
            } catch (Throwable t) {
                fail("aws-chunked body " + new String(body, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n") + " -> " + t, t);
            }
            runs++;
        }
        assertTrue(runs > 1000, "too few iterations: " + runs);
        assertTrue(decoded >= 0);
    }

    @Test
    void anUnsignedChunkedBodyWithAHugeDeclaredChunkIsRejectedOrReadIncrementally() {
        // the declared size comes from the unverified header: it must not be allocated up front
        for (String size : new String[]{"7FFFFFFF", "800001", "FFFFFFFFFFFF", "-1", ""}) {
            byte[] body = (size + "\r\nabc\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            try (AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body), null, null)) {
                in.readAllBytes();
                fail("chunk size '" + size + "' was accepted");
            } catch (IOException expected) {
                assertEquals(expected.getClass().getSimpleName().isEmpty(), false);
            }
        }
    }

    private static byte[] randomBytes(Random rnd, int max) {
        byte[] b = new byte[rnd.nextInt(max + 1)];
        rnd.nextBytes(b);
        return b;
    }
}
