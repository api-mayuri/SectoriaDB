package org.example.s3.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class S3ObjectEntry {

    @JacksonXmlProperty(localName = "Key")
    private String key;

    @JacksonXmlProperty(localName = "LastModified")
    private String lastModified;

    @JacksonXmlProperty(localName = "ETag")
    private String etag;

    @JacksonXmlProperty(localName = "Size")
    private long size;

    @JacksonXmlProperty(localName = "StorageClass")
    private String storageClass = "STANDARD";

    @JacksonXmlProperty(localName = "Owner")
    private Owner owner;

    public S3ObjectEntry() {}

    public S3ObjectEntry(String key, String lastModified, String etag, long size) {
        this.key = key;
        this.lastModified = lastModified;
        this.etag = etag;
        this.size = size;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getStorageClass() { return storageClass; }
    public void setStorageClass(String storageClass) { this.storageClass = storageClass; }

    public Owner getOwner() { return owner; }
    public void setOwner(Owner owner) { this.owner = owner; }
}
