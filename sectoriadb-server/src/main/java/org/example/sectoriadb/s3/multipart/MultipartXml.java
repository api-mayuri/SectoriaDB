package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.s3.http.HttpDates;
import org.example.sectoriadb.s3.xml.XmlSupport;

import java.time.Instant;

/** The XML bodies of the multipart responses (S3 namespace, no whitespace). */
final class MultipartXml {

    static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    private static final String PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private MultipartXml() {}

    static String initiateResult(String bucket, String key, String uploadId) {
        return PROLOG
                + "<InitiateMultipartUploadResult xmlns=\"" + NS + "\">"
                + "<Bucket>" + XmlSupport.escape(bucket) + "</Bucket>"
                + "<Key>" + XmlSupport.escape(key) + "</Key>"
                + "<UploadId>" + uploadId + "</UploadId>"
                + "</InitiateMultipartUploadResult>";
    }

    static String copyPartResult(Instant lastModified, String etag, String checksumXml) {
        return PROLOG
                + "<CopyPartResult xmlns=\"" + NS + "\">"
                + "<LastModified>" + HttpDates.isoDate(lastModified) + "</LastModified>"
                + "<ETag>\"" + etag + "\"</ETag>" + checksumXml
                + "</CopyPartResult>";
    }

    static String completeResult(String location, String bucket, String key, String etag, String checksumXml) {
        return PROLOG
                + "<CompleteMultipartUploadResult xmlns=\"" + NS + "\">"
                + "<Location>" + XmlSupport.escape(location) + "</Location>"
                + "<Bucket>" + XmlSupport.escape(bucket) + "</Bucket>"
                + "<Key>" + XmlSupport.escape(key) + "</Key>"
                + "<ETag>" + XmlSupport.escape(etag) + "</ETag>" + checksumXml
                + "</CompleteMultipartUploadResult>";
    }
}
