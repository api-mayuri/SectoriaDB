package org.example.sectoriadb.s3.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "Error")
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class S3Error {

    @JacksonXmlProperty(localName = "Code")
    private String code;

    @JacksonXmlProperty(localName = "Message")
    private String message;

    @JacksonXmlProperty(localName = "Resource")
    private String resource;

    @JacksonXmlProperty(localName = "RequestId")
    private String requestId;

    public S3Error() {}

    public S3Error(String code, String message, String resource, String requestId) {
        this.code = code;
        this.message = message;
        this.resource = resource;
        this.requestId = requestId;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
