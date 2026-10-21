package org.example.sectoriadb.s3.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.sectoriadb.observability.RequestIds;
import org.example.sectoriadb.observability.RequestObservabilityFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the headers every S3 response carries in one place instead of in every handler: {@code Server: SectoriaDB} and
 * the {@code x-amz-request-id} (the observability filter, which runs first, has set it already; it is only filled in
 * here if that filter is not in the chain).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class S3CommonHeadersFilter extends OncePerRequestFilter {

    public static final String SERVER_NAME = "SectoriaDB";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(S3Headers.SERVER, SERVER_NAME);
        if (response.getHeader(RequestObservabilityFilter.REQUEST_ID_HEADER) == null) {
            response.setHeader(RequestObservabilityFilter.REQUEST_ID_HEADER, RequestIds.current());
        }
        chain.doFilter(request, response);
    }
}
