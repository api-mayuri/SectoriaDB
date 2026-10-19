package org.example.sectoriadb.s3.xml;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** The single place that creates XML parsers for request bodies; XXE and entity expansion are disabled. */
public final class XmlSupport {

    /** Malformed input is the caller's 400: only throw, the default handler would print "[Fatal Error]" to stderr per request. */
    private static final ErrorHandler THROW_QUIETLY = new ErrorHandler() {
        @Override public void warning(SAXParseException e) { }
        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
    };

    /** Deepest element nesting accepted in a request document (the legitimate ones need 4). */
    public static final int MAX_ELEMENT_DEPTH = 32;

    private XmlSupport() {}

    public static DocumentBuilder newSecureDocumentBuilder(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // a request document has 3-4 levels; getTextContent() and friends recurse, so a 300 000-level body is a StackOverflowError
        dbf.setAttribute("jdk.xml.maxElementDepth", String.valueOf(MAX_ELEMENT_DEPTH));
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setNamespaceAware(namespaceAware);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(THROW_QUIETLY);
        return db;
    }

    /** Escapes text for use inside an XML element or attribute value; null becomes the empty string. */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
