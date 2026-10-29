package org.example.sectoriadb.s3.xml;

import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class BucketEntry {

    @JacksonXmlProperty(localName = "Name")
    private String name;

    @JacksonXmlProperty(localName = "CreationDate")
    private String creationDate;

    public BucketEntry() {}

    public BucketEntry(String name, String creationDate) {
        this.name = name;
        this.creationDate = creationDate;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreationDate() { return creationDate; }
    public void setCreationDate(String creationDate) { this.creationDate = creationDate; }
}
