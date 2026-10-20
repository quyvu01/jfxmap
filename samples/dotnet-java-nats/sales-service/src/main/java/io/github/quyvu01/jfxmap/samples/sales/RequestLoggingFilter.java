package io.github.quyvu01.jfxmap.samples.sales;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** One line per HTTP call, as catalog-service logs them: "HTTP GET /orders responded 200 in 35 ms". */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            var query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
            LOG.info("HTTP {} {}{} responded {} in {} ms", request.getMethod(), request.getRequestURI(), query,
                    response.getStatus(), (System.nanoTime() - start) / 1_000_000);
        }
    }
}
