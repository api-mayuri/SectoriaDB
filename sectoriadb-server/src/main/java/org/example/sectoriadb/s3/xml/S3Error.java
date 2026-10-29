package org.example.sectoriadb.s3.xml;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "Error")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class S3Error {

    @JacksonXmlProperty(localName = "Code")
    private String code;

    @JacksonXmlProperty(localName = "Message")
    private String message;

    @JacksonXmlProperty(localName = "Resource")
    private String resource;

    @JacksonXmlProperty(localName = "RequestId")
    private String requestId;

    @JacksonXmlProperty(localName = "ProposedSize")
    private Long proposedSize;

    @JacksonXmlProperty(localName = "MinSizeAllowed")
    private Long minSizeAllowed;

    @JacksonXmlProperty(localName = "PartNumber")
    private Integer partNumber;

    public S3Error() {}

    public S3Error(String code, String message, String resource, String requestId) {
        this.code = code;
        this.message = message;
        this.resource = resource;
        this.requestId = requestId;
    }

    /** EntityTooSmall details. */
    public S3Error withPartSizes(int partNumber, long proposedSize, long minSizeAllowed) {
        this.partNumber = partNumber;
        this.proposedSize = proposedSize;
        this.minSizeAllowed = minSizeAllowed;
        return this;
    }

    public Long getProposedSize() { return proposedSize; }
    public Long getMinSizeAllowed() { return minSizeAllowed; }
    public Integer getPartNumber() { return partNumber; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
