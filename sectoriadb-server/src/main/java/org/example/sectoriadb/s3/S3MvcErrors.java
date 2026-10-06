package org.example.sectoriadb.s3;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Maps HTTP statuses and Spring MVC failures to S3 error codes. Used by {@link S3ExceptionHandler} (failures inside
 * a controller call: bad parameter values, wrong method on a known path, ...) and by {@link S3ErrorController}
 * (failures before any controller was chosen: unknown method on a path, unsupported media type, ...).
 */
final class S3MvcErrors {

    private S3MvcErrors() {}

    /** S3 error code for a status that Spring (or the container) produced on its own. */
    static String codeFor(HttpStatusCode status) {
        int v = status.value();
        if (v >= 500) return "InternalError";
        return switch (v) {
            case 400 -> "InvalidRequest";
            case 401, 403 -> "AccessDenied";
            case 404 -> "NotFound";
            case 405 -> "MethodNotAllowed";
            case 406 -> "NotAcceptable";
            case 408 -> "RequestTimeout";
            case 411 -> "MissingContentLength";
            case 412 -> "PreconditionFailed";
            case 413 -> "EntityTooLarge";
            case 414 -> "UriTooLong";
            case 415 -> "UnsupportedMediaType";
            case 416 -> "InvalidRange";
            case 431 -> "RequestHeaderFieldsTooLarge";
            default -> HttpStatus.resolve(v) != null ? "InvalidRequest" : "InternalError";
        };
    }

    static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 405 -> "The specified method is not allowed against this resource.";
            case 404 -> "The requested resource was not found.";
            case 406 -> "The requested representation is not available.";
            case 415 -> "The request content type is not supported.";
            case 431 -> "The request headers are too large.";
            default -> status.value() >= 500
                    ? "We encountered an internal error. Please try again."
                    : "The request is not valid.";
        };
    }
}
