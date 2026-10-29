package org.example.sectoriadb.s3.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.observability.ObservabilityAttributes;
import org.example.sectoriadb.observability.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * {@code sectoriadb.s3.require-secure-transport=true}: every S3 request that did not arrive over HTTPS is answered
 * {@code 403 InsecureTransport} before authentication (so a secret-bearing request is never even looked at).
 *
 * "Arrived over HTTPS" is {@link HttpServletRequest#isSecure()}. The server itself speaks plain HTTP, so the flag comes
 * from the TLS proxy's {@code X-Forwarded-Proto} through Tomcat's RemoteIpValve ({@code server.forward-headers-strategy=native}),
 * which honours the header only from {@code internal-proxies} (private ranges by default,
 * {@code server.tomcat.remoteip.internal-proxies}): from any other peer the header is ignored. See docs/architecture/security.md.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
public class SecureTransportFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecureTransportFilter.class);
    public static final String ERROR_CODE = "InsecureTransport";

    private final boolean required;

    public SecureTransportFilter(@Value("${sectoriadb.s3.require-secure-transport:false}") boolean required) {
        this(required, false);
    }

    /**
     * @param tlsInProcess {@code server.ssl.enabled}: Tomcat terminates TLS itself. Without it and without
     *                     {@code require-secure-transport} the server accepts S3 requests over plain HTTP, which only a
     *                     local development setup should do: the start-up log says so (the compose file requires TLS).
     */
    @Autowired
    public SecureTransportFilter(@Value("${sectoriadb.s3.require-secure-transport:false}") boolean required,
                                 @Value("${server.ssl.enabled:false}") boolean tlsInProcess) {
        this.required = required;
        if (!required && !tlsInProcess) {
            log.warn("S3 requests are accepted over plain HTTP: put the TLS proxy in front (docker-compose.yml with nginx) "
                    + "and set sectoriadb.s3.require-secure-transport=true; without it keys, signatures and data travel unencrypted");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!required || request.isSecure()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(403);
        response.setContentType("application/xml;charset=UTF-8");
        ObservabilityAttributes.noteErrorCode(request, ERROR_CODE);
        try (PrintWriter w = response.getWriter()) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<Error><Code>" + ERROR_CODE + "</Code>");
            w.write("<Message>This server only accepts requests over HTTPS (sectoriadb.s3.require-secure-transport=true).</Message>");
            w.write("<RequestId>" + RequestIds.current() + "</RequestId>");
            w.write("</Error>");
        }
    }
}
