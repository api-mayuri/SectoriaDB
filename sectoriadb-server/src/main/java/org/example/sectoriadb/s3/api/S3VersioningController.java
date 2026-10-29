package org.example.sectoriadb.s3.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.catalog.LifecycleRule;
import org.example.sectoriadb.catalog.PoolEntity;
import org.example.sectoriadb.s3.error.S3Exception;
import org.example.sectoriadb.s3.xml.XmlSupport;
import org.example.sectoriadb.storage.PoolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bucket versioning and lifecycle configuration (docs/architecture/versioning.md):
 * <ul>
 *   <li>PUT/GET /{bucket}?versioning — {@code Enabled} or {@code Suspended}; MFA Delete is not supported;</li>
 *   <li>PUT/GET/DELETE /{bucket}?lifecycle — rules with {@code Filter/Prefix} (or the old {@code Prefix}),
 *       {@code NoncurrentVersionExpiration/NoncurrentDays} and {@code Expiration/ExpiredObjectDeleteMarker}; any other
 *       action is {@code 501 NotImplemented} rather than stored and silently ignored.</li>
 * </ul>
 */
@RestController
public class S3VersioningController {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    private static final Set<String> RULE_ELEMENTS = Set.of("ID", "Status", "Filter", "Prefix", "NoncurrentVersionExpiration", "Expiration");

    private final PoolService poolService;
    private final S3Lookup lookup;

    public S3VersioningController(PoolService poolService, S3Lookup lookup) {
        this.poolService = poolService;
        this.lookup = lookup;
    }

    // ── Versioning ────────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "versioning")
    public ResponseEntity<Void> putBucketVersioning(@PathVariable String bucket, HttpServletRequest request) throws IOException {
        lookup.requireBucket(bucket);
        Element root = parse(request, "VersioningConfiguration");
        String status = text(root, "Status");
        String mfa = text(root, "MfaDelete");
        if ("Enabled".equals(mfa)) throw S3Exception.notImplemented("MFA Delete is not supported");
        if (!"Enabled".equals(status) && !"Suspended".equals(status)) {
            throw new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML",
                    "The versioning Status must be Enabled or Suspended.", "/" + bucket);
        }
        poolService.setVersioning(bucket, status);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "versioning", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketVersioning(@PathVariable String bucket) {
        PoolEntity pool = lookup.requireBucket(bucket);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<VersioningConfiguration xmlns=\"" + NS + "\">"
                + (pool.getVersioning() == null ? "" : "<Status>" + pool.getVersioning() + "</Status>")
                + "</VersioningConfiguration>";
        return ResponseEntity.ok().body(xml);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PutMapping(value = {"/{bucket}", "/{bucket}/"}, params = "lifecycle")
    public ResponseEntity<Void> putBucketLifecycle(@PathVariable String bucket, HttpServletRequest request) throws IOException {
        lookup.requireBucket(bucket);
        Element root = parse(request, "LifecycleConfiguration");
        List<LifecycleRule> rules = new ArrayList<>();
        for (Element rule : children(root, "Rule")) {
            for (Element c : children(rule, null)) {
                if (!RULE_ELEMENTS.contains(c.getLocalName() != null ? c.getLocalName() : c.getTagName())) {
                    throw S3Exception.notImplemented("Lifecycle element " + c.getTagName() + " is not supported");
                }
            }
            String id = text(rule, "ID");
            String status = text(rule, "Status");
            if (!"Enabled".equals(status) && !"Disabled".equals(status)) throw malformed("Status must be Enabled or Disabled");
            String prefix = text(rule, "Prefix");
            Element filter = child(rule, "Filter");
            if (filter != null) {
                for (Element c : children(filter, null)) {
                    if (!"Prefix".equals(c.getLocalName() != null ? c.getLocalName() : c.getTagName())) {
                        throw S3Exception.notImplemented("Lifecycle filter " + c.getTagName() + " is not supported (only Prefix)");
                    }
                }
                prefix = text(filter, "Prefix");
            }
            Integer days = null;
            Element nve = child(rule, "NoncurrentVersionExpiration");
            if (nve != null) {
                for (Element c : children(nve, null)) {
                    String n = c.getLocalName() != null ? c.getLocalName() : c.getTagName();
                    if (!"NoncurrentDays".equals(n)) throw S3Exception.notImplemented("NoncurrentVersionExpiration/" + n + " is not supported");
                }
                days = positive(text(nve, "NoncurrentDays"), "NoncurrentDays");
            }
            boolean expiredMarker = false;
            Element exp = child(rule, "Expiration");
            if (exp != null) {
                for (Element c : children(exp, null)) {
                    String n = c.getLocalName() != null ? c.getLocalName() : c.getTagName();
                    if (!"ExpiredObjectDeleteMarker".equals(n)) {
                        throw S3Exception.notImplemented("Expiration/" + n + " is not supported (only ExpiredObjectDeleteMarker)");
                    }
                }
                expiredMarker = "true".equals(text(exp, "ExpiredObjectDeleteMarker"));
            }
            if (days == null && !expiredMarker) throw malformed("A rule needs NoncurrentVersionExpiration or Expiration/ExpiredObjectDeleteMarker");
            rules.add(new LifecycleRule(id, "Enabled".equals(status), prefix, days, expiredMarker));
        }
        if (rules.isEmpty()) throw malformed("A lifecycle configuration needs at least one Rule");
        if (rules.size() > 1000) throw malformed("At most 1000 rules");
        poolService.setLifecycle(bucket, rules);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = {"/{bucket}", "/{bucket}/"}, params = "lifecycle", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBucketLifecycle(@PathVariable String bucket) {
        PoolEntity pool = lookup.requireBucket(bucket);
        if (pool.getLifecycle() == null || pool.getLifecycle().isEmpty()) {
            throw new S3Exception(HttpStatus.NOT_FOUND, "NoSuchLifecycleConfiguration",
                    "The lifecycle configuration does not exist.", "/" + bucket);
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<LifecycleConfiguration xmlns=\"" + NS + "\">");
        for (LifecycleRule r : pool.getLifecycle()) {
            xml.append("<Rule>");
            if (r.id() != null) xml.append("<ID>").append(XmlSupport.escape(r.id())).append("</ID>");
            xml.append("<Filter><Prefix>").append(XmlSupport.escape(r.prefix())).append("</Prefix></Filter>")
               .append("<Status>").append(r.enabled() ? "Enabled" : "Disabled").append("</Status>");
            if (r.expiredObjectDeleteMarker()) xml.append("<Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>");
            if (r.noncurrentDays() != null) {
                xml.append("<NoncurrentVersionExpiration><NoncurrentDays>").append(r.noncurrentDays()).append("</NoncurrentDays></NoncurrentVersionExpiration>");
            }
            xml.append("</Rule>");
        }
        xml.append("</LifecycleConfiguration>");
        return ResponseEntity.ok().body(xml.toString());
    }

    @DeleteMapping(value = {"/{bucket}", "/{bucket}/"}, params = "lifecycle")
    public ResponseEntity<Void> deleteBucketLifecycle(@PathVariable String bucket) {
        lookup.requireBucket(bucket);
        poolService.setLifecycle(bucket, null);
        return ResponseEntity.noContent().build();
    }

    // ── XML ───────────────────────────────────────────────────────────────────

    private static Element parse(HttpServletRequest request, String rootName) throws IOException {
        Document doc;
        try {
            doc = XmlSupport.newSecureDocumentBuilder(true).parse(RequestBodies.xmlBody(request));
        } catch (S3Exception e) {
            throw e;
        } catch (Exception e) {
            throw malformed("The XML you provided was not well-formed or did not validate against our published schema.");
        }
        Element root = doc.getDocumentElement();
        String name = root.getLocalName() != null ? root.getLocalName() : root.getTagName();
        if (!rootName.equals(name)) throw malformed("Expected " + rootName);
        return root;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e) {
                String local = e.getLocalName() != null ? e.getLocalName() : e.getTagName();
                if (name == null || name.equals(local)) out.add(e);
            }
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }

    private static int positive(String v, String what) {
        try {
            int n = Integer.parseInt(v);
            if (n > 0) return n;
        } catch (NumberFormatException | NullPointerException e) {
            // reported below
        }
        throw S3Exception.invalidArgument(what + " must be a positive integer");
    }

    private static S3Exception malformed(String message) {
        return new S3Exception(HttpStatus.BAD_REQUEST, "MalformedXML", message);
    }
}
