package org.example.sectoriadb.s3.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.util.List;

@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class ListAllMyBucketsResult {

    @JacksonXmlProperty(localName = "Owner")
    private Owner owner;

    @JacksonXmlElementWrapper(localName = "Buckets")
    @JacksonXmlProperty(localName = "Bucket")
    private List<BucketEntry> buckets;

    public ListAllMyBucketsResult() {}

    public ListAllMyBucketsResult(Owner owner, List<BucketEntry> buckets) {
        this.owner = owner;
        this.buckets = buckets;
    }

    public Owner getOwner() { return owner; }
    public void setOwner(Owner owner) { this.owner = owner; }

    public List<BucketEntry> getBuckets() { return buckets; }
    public void setBuckets(List<BucketEntry> buckets) { this.buckets = buckets; }

    /** Default namespace declaration: children inherit it, as in real S3 responses. */
    @JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    public String getXmlns() { return "http://s3.amazonaws.com/doc/2006-03-01/"; }
}
