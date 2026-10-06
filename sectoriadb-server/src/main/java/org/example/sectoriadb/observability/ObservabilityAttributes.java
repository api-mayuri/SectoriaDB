package org.example.sectoriadb.observability;

import jakarta.servlet.http.HttpServletRequest;
import org.example.sectoriadb.s3.auth.AuthFailure;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.regex.Pattern;

/**
 * Small side channel from the S3 layer to {@link RequestObservabilityFilter}: the layers that know why a request
 * failed (the SigV4 filter, the exception handler) leave the S3 error code / auth failure reason on the request, and
 * the outermost filter turns them into metrics once the response is complete.
 */
public final class ObservabilityAttributes {

    /** S3 error code written to the response (String). */
    public static final String ERROR_CODE = "sectoriadb.observability.errorCode";
    /** {@link AuthFailure} that rejected the request. */
    public static final String AUTH_FAILURE = "sectoriadb.observability.authFailure";

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9]{1,48}");

    private ObservabilityAttributes() {
    }

    public static void noteErrorCode(HttpServletRequest request, String code) {
        if (request != null && code != null) request.setAttribute(ERROR_CODE, code);
    }

    /** For code that has no request at hand (exception handlers): uses the thread's current request. */
    public static void noteErrorCode(String code) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null && code != null) ra.setAttribute(ERROR_CODE, code, RequestAttributes.SCOPE_REQUEST);
    }

    public static void noteAuthFailure(AuthFailure reason) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null && reason != null) ra.setAttribute(AUTH_FAILURE, reason, RequestAttributes.SCOPE_REQUEST);
    }

    /** Guards the {@code code} label: only short alphanumeric identifiers (S3 error codes) are ever used as a label value. */
    public static String safeCode(String code) {
        return code != null && SAFE_CODE.matcher(code).matches() ? code : "Other";
    }
}
