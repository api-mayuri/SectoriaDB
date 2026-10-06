package org.example.sectoriadb;

import org.example.sectoriadb.s3.auth.SigV4Utils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/** Minimal SigV4 client used by the security tests (header auth, presigned URLs, aws-chunked bodies). */
final class SigV4TestClient {

    static final String REGION = "us-east-1";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    final int port;
    final String accessKey;
    final String secretKey;
    final HttpClient http = HttpClient.newHttpClient();

    SigV4TestClient(int port, String accessKey, String secretKey) {
        this.port = port;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /** Result of signing the request headers. */
    record Signed(String authorization, String timestamp, String scope, String signature, byte[] signingKey) {}

    Signed sign(String method, String path, String query, String payloadHash, Instant when) {
        String ts = TS.format(when);
        String date = DAY.format(when);
        String scope = date + "/" + REGION + "/s3/aws4_request";
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "localhost:" + port);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", ts);
        String canonical = SigV4Utils.buildCanonicalRequest(method, path, query, headers, payloadHash);
        String sts = SigV4Utils.buildStringToSign(ts, scope, SigV4Utils.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] key = SigV4Utils.deriveSigningKey(secretKey, date, REGION, "s3");
        String sig = SigV4Utils.computeSignature(key, sts);
        String auth = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + sig;
        return new Signed(auth, ts, scope, sig, key);
    }

    HttpResponse<String> request(String method, String path, String query, String signedPayloadHash,
                                 byte[] body) throws Exception {
        return request(method, path, query, signedPayloadHash, body, Instant.now());
    }

    HttpResponse<String> request(String method, String path, String query, String signedPayloadHash,
                                 byte[] body, Instant when) throws Exception {
        Signed s = sign(method, path, query, signedPayloadHash, when);
        URI uri = URI.create("http://localhost:" + port + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .header("Authorization", s.authorization())
                .header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", signedPayloadHash);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        return http.send(b.build(), BodyHandlers.ofString());
    }

    HttpResponse<String> put(String path, byte[] body, String signedPayloadHash) throws Exception {
        return request("PUT", path, null, signedPayloadHash, body);
    }

    static String sha256(byte[] data) { return SigV4Utils.sha256Hex(data); }

    /** Builds an aws-chunked body, signing each chunk; {@code tamperIndex} flips a byte AFTER signing. */
    static byte[] chunkedBody(Signed s, byte[][] chunks, int tamperIndex, boolean omitFinalChunk,
                              String trailerLine, boolean signTrailer) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String prev = s.signature();
        String empty = SigV4Utils.sha256HexEmpty();
        for (int i = 0; i < chunks.length; i++) {
            prev = writeChunk(out, s, prev, empty, chunks[i], i == tamperIndex, true);
        }
        if (!omitFinalChunk) {
            prev = writeChunk(out, s, prev, empty, new byte[0], false, false);
            if (trailerLine != null) {
                // writeChunk wrote "0;chunk-signature=..\r\n" + "\r\n"; rewrite: strip the last CRLF
                byte[] all = out.toByteArray();
                out.reset();
                out.write(all, 0, all.length - 2);
                out.write((trailerLine + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (signTrailer) {
                    String canonical = trailerLine + "\n";
                    String sts = "AWS4-HMAC-SHA256-TRAILER\n" + s.timestamp() + "\n" + s.scope() + "\n" + prev + "\n"
                            + SigV4Utils.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
                    String tsig = SigV4Utils.computeSignature(s.signingKey(), sts);
                    out.write(("x-amz-trailer-signature:" + tsig + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    private static String writeChunk(ByteArrayOutputStream out, Signed s, String prev, String empty,
                                     byte[] data, boolean tamper, boolean ignored) throws Exception {
        String sts = "AWS4-HMAC-SHA256-PAYLOAD\n" + s.timestamp() + "\n" + s.scope() + "\n" + prev + "\n"
                + empty + "\n" + SigV4Utils.sha256Hex(data);
        String sig = SigV4Utils.computeSignature(s.signingKey(), sts);
        byte[] sent = data.clone();
        if (tamper && sent.length > 0) sent[0] ^= 0x01;
        out.write((Integer.toHexString(data.length) + ";chunk-signature=" + sig + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(sent);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        return sig;
    }

    HttpResponse<String> putChunked(String path, String streamingType, byte[][] chunks, int tamperIndex,
                                    boolean omitFinal, String trailerLine, boolean signTrailer) throws Exception {
        return putChunked(path, streamingType, chunks, tamperIndex, omitFinal, trailerLine, signTrailer, true);
    }

    /** {@code announceTrailer}: send x-amz-trailer with the name of the trailer line, as SDKs do. */
    HttpResponse<String> putChunked(String path, String streamingType, byte[][] chunks, int tamperIndex,
                                    boolean omitFinal, String trailerLine, boolean signTrailer,
                                    boolean announceTrailer) throws Exception {
        Signed s = sign("PUT", path, null, streamingType, Instant.now());
        byte[] body = chunkedBody(s, chunks, tamperIndex, omitFinal, trailerLine, signTrailer);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", s.authorization())
                .header("x-amz-date", s.timestamp())
                .header("x-amz-content-sha256", streamingType)
                .header("Content-Encoding", "aws-chunked");
        if (trailerLine != null && announceTrailer) b.header("x-amz-trailer", trailerLine.substring(0, trailerLine.indexOf(':')));
        return http.send(b.PUT(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }

    /** Presigned GET URL (query-string auth). */
    URI presign(String path, Instant signedAt, long expires) {
        String ts = TS.format(signedAt);
        String date = DAY.format(signedAt);
        String scope = date + "/" + REGION + "/s3/aws4_request";
        String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=" + URLEncoder.encode(accessKey + "/" + scope, StandardCharsets.UTF_8)
                + "&X-Amz-Date=" + ts
                + "&X-Amz-Expires=" + expires
                + "&X-Amz-SignedHeaders=host";
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "localhost:" + port);
        String canonical = SigV4Utils.buildCanonicalRequest("GET", path, query, headers, SigV4Utils.UNSIGNED_PAYLOAD);
        String sts = SigV4Utils.buildStringToSign(ts, scope, SigV4Utils.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)));
        String sig = SigV4Utils.computeSignature(SigV4Utils.deriveSigningKey(secretKey, date, REGION, "s3"), sts);
        return URI.create("http://localhost:" + port + path + "?" + query + "&X-Amz-Signature=" + sig);
    }

    HttpResponse<String> get(URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).GET().build(), BodyHandlers.ofString());
    }
}
