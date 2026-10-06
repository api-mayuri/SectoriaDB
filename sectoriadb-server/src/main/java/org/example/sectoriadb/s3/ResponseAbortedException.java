package org.example.sectoriadb.s3;

/**
 * Thrown when a response body cannot be completed after the headers were already sent (e.g. an integrity check
 * failed while streaming a GET). It is deliberately NOT turned into an error document: it propagates to the
 * servlet container, which then closes the connection so that the client sees a failed transfer.
 */
public class ResponseAbortedException extends RuntimeException {

    public ResponseAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
