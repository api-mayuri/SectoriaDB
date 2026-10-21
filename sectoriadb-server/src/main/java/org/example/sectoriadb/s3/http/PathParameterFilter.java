package org.example.sectoriadb.s3.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.observability.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Refuses every request whose raw URI path contains {@code ;} (a servlet "path parameter") with
 * {@code 400 InvalidURI}, before authentication and authorization, for signed and anonymous requests alike.
 *
 * Why: the SigV4 canonical URI, the IAM/bucket-policy authorizer and {@link S3Request#extractKey} all work on the raw
 * request URI, while Spring's {@code /{bucket}} mapping strips {@code ;x} from a path segment. {@code GET /secret;x?list-type=2}
 * would therefore be authorized against the resource {@code arn:aws:s3:::secret;x} (a Deny on {@code secret} does not match)
 * and then executed against the bucket {@code secret}. S3 clients percent-encode a literal {@code ;} in object keys as
 * {@code %3B}; that stays valid, only the raw semicolon is refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 13)
public class PathParameterFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "InvalidURI";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || uri.indexOf(';') < 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(400);
        response.setContentType("application/xml;charset=UTF-8");
        ObservabilityAttributes.noteErrorCode(request, ERROR_CODE);
        try (PrintWriter w = response.getWriter()) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<Error><Code>" + ERROR_CODE + "</Code>");
            w.write("<Message>The request path must not contain ';' (encode it as %3B).</Message>");
            w.write("<RequestId>" + RequestIds.current() + "</RequestId>");
            w.write("</Error>");
        }
    }
}
