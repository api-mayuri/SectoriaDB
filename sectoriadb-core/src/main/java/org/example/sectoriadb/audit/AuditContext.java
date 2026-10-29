package org.example.sectoriadb.audit;

import org.slf4j.MDC;

/**
 * Who a logged operation was done for. The HTTP layer puts these values into the logging context (MDC) of the request
 * thread; the operation log copies them into every entry written on that thread. Work of the server itself (garbage
 * collection, resize) runs on other threads and is recorded with the actor {@value #SYSTEM}.
 */
public final class AuditContext {

    /** MDC key of the request id (also printed in every log line of the request). */
    public static final String REQUEST_ID = "requestId";
    /** MDC key of the access key the request was signed with; absent for anonymous requests. */
    public static final String ACCESS_KEY = "accessKey";
    /** MDC key of the client address (the real client behind the trusted proxy). */
    public static final String CLIENT = "client";

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    private AuditContext() {
    }

    /** The actor of an operation on the current thread: the access key, {@value #ANONYMOUS} or {@value #SYSTEM}. */
    static String actor() {
        String key = MDC.get(ACCESS_KEY);
        if (key != null) return key;
        return MDC.get(REQUEST_ID) != null ? ANONYMOUS : SYSTEM;
    }
}
