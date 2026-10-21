package org.example.sectoriadb.s3.xml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JacksonXmlRootElement(localName = "ListBucketResult")
@JsonInclude(NON_NULL)
public class ListBucketResult {

    @JacksonXmlProperty(localName = "Name")
    private String name;

    @JacksonXmlProperty(localName = "Prefix")
    private String prefix = "";

    @JacksonXmlProperty(localName = "Delimiter")
    private String delimiter;

    @JacksonXmlProperty(localName = "MaxKeys")
    private int maxKeys = 1000;

    @JacksonXmlProperty(localName = "IsTruncated")
    private boolean truncated = false;

    @JacksonXmlProperty(localName = "KeyCount")
    private int keyCount;

    @JacksonXmlProperty(localName = "EncodingType")
    private String encodingType;

    @JacksonXmlProperty(localName = "StartAfter")
    private String startAfter;

    @JacksonXmlProperty(localName = "ContinuationToken")
    private String continuationToken;

    @JacksonXmlProperty(localName = "NextContinuationToken")
    private String nextContinuationToken;

    @JacksonXmlProperty(localName = "Marker")
    private String marker;

    @JacksonXmlProperty(localName = "NextMarker")
    private String nextMarker;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Contents")
    private List<S3ObjectEntry> contents;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "CommonPrefixes")
    private List<CommonPrefix> commonPrefixes;

    public ListBucketResult() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public String getDelimiter() { return delimiter; }
    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }

    public int getMaxKeys() { return maxKeys; }
    public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public int getKeyCount() { return keyCount; }
    public void setKeyCount(int keyCount) { this.keyCount = keyCount; }

    public String getContinuationToken() { return continuationToken; }
    public void setContinuationToken(String continuationToken) { this.continuationToken = continuationToken; }

    public String getNextContinuationToken() { return nextContinuationToken; }
    public void setNextContinuationToken(String nextContinuationToken) { this.nextContinuationToken = nextContinuationToken; }

    public String getEncodingType() { return encodingType; }
    public void setEncodingType(String encodingType) { this.encodingType = encodingType; }

    public String getStartAfter() { return startAfter; }
    public void setStartAfter(String startAfter) { this.startAfter = startAfter; }

    public String getMarker() { return marker; }
    public void setMarker(String marker) { this.marker = marker; }

    public String getNextMarker() { return nextMarker; }
    public void setNextMarker(String nextMarker) { this.nextMarker = nextMarker; }

    public List<S3ObjectEntry> getContents() { return contents; }
    public void setContents(List<S3ObjectEntry> contents) { this.contents = contents; }

    public List<CommonPrefix> getCommonPrefixes() { return commonPrefixes; }
    public void setCommonPrefixes(List<CommonPrefix> commonPrefixes) { this.commonPrefixes = commonPrefixes; }

    /**
     * Inner class for CommonPrefixes elements.
     * Each CommonPrefix contains just the Prefix value.
     */
    @JsonInclude(NON_NULL)
    public static class CommonPrefix {
        @JacksonXmlProperty(localName = "Prefix")
        private String prefix;

        public CommonPrefix() {}
        public CommonPrefix(String prefix) { this.prefix = prefix; }

        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
    }

    /** Default namespace declaration: children inherit it, as in real S3 responses. */
    @JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    public String getXmlns() { return "http://s3.amazonaws.com/doc/2006-03-01/"; }
}
