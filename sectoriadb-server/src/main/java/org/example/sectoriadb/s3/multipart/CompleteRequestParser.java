package org.example.sectoriadb.s3.multipart;

import org.example.sectoriadb.checksum.ChecksumAlgorithm;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;

/** Reads the {@code <CompleteMultipartUpload>} document (the body is size-bounded by the caller). */
final class CompleteRequestParser {

    private CompleteRequestParser() {}

    static ParsedParts parse(InputStream body) {
        try {
            DocumentBuilder db = XmlSupport.newSecureDocumentBuilder(false);
            Document doc = db.parse(body);
            NodeList parts = doc.getElementsByTagName("Part");
            List<Integer> result = new ArrayList<>();
            Map<Integer, String> etags = new HashMap<>();
            Map<Integer, Map<ChecksumAlgorithm, String>> checksums = new HashMap<>();

            for (int i = 0; i < parts.getLength(); i++) {
                Element partElem = (Element) parts.item(i);
                int partNum = 0;
                String etag = null;
                Map<ChecksumAlgorithm, String> partChecksums = new EnumMap<>(ChecksumAlgorithm.class);

                NodeList children = partElem.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    String nodeName = children.item(j).getNodeName();
                    String content = children.item(j).getTextContent().trim();
                    if ("PartNumber".equals(nodeName)) {
                        partNum = Integer.parseInt(content);
                    } else if ("ETag".equals(nodeName)) {
                        etag = content;
                    } else if (nodeName.startsWith("Checksum") && !content.isEmpty()) {
                        ChecksumAlgorithm.fromAwsName(nodeName.substring("Checksum".length()))
                                .ifPresent(a -> partChecksums.put(a, content));
                    }
                }

                if (partNum < 1 || partNum > ParsedParts.MAX_PARTS) {
                    throw S3Exception.invalidArgument("Part number must be an integer between 1 and 10000, inclusive");
                }
                result.add(partNum);
                if (etag != null) {
                    etags.put(partNum, etag);
                }
                if (!partChecksums.isEmpty()) checksums.put(partNum, partChecksums);
            }

            // NOT sorted: S3 requires ascending order and answers InvalidPartOrder otherwise
            return new ParsedParts(result, etags, checksums);
        } catch (S3Exception e) {
            throw e;
        } catch (Exception e) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "The XML you provided was not well-formed or did not validate against our published schema");
        }
    }
}
