package com.erlan.testtask.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RequiredArgsConstructor
public class HttpLoggingFilter extends OncePerRequestFilter {

    private final HttpBodySanitizer bodySanitizer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String method = request.getMethod();
        String path = safePath(request.getRequestURI());

        if (!captureBody(request)) {
            try {
                chain.doFilter(request, response);
            } finally {
                log.info("HTTP REQUEST | {} {} | body=[omitted]", method, path);
                log.info("HTTP RESPONSE | {} {} | status={} | durationMs={} | body=[omitted]",
                        method, path, response.getStatus(), elapsedMillis(start));
            }
            return;
        }

        ContentCachingRequestWrapper cachedRequest =
                new ContentCachingRequestWrapper(request, HttpBodySanitizer.CAPTURE_LIMIT_BYTES);
        BoundedResponseWrapper capturedResponse =
                new BoundedResponseWrapper(response, HttpBodySanitizer.CAPTURE_LIMIT_BYTES);
        try {
            chain.doFilter(cachedRequest, capturedResponse);
        } finally {
            capturedResponse.flushCapture();
            log.info("HTTP REQUEST | {} {} | body={}", method, path,
                    bodySanitizer.sanitize(cachedRequest.getContentAsByteArray(), request.getContentType()));
            log.info("HTTP RESPONSE | {} {} | status={} | durationMs={} | body={}",
                    method, path, capturedResponse.getStatus(), elapsedMillis(start),
                    bodySanitizer.sanitize(capturedResponse.getCapturedContent(), capturedResponse.getContentType()));
        }
    }

    private boolean captureBody(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contentType = request.getContentType();
        String accept = request.getHeader("Accept");
        return ("/api/tasks".equals(path) || path.startsWith("/api/tasks/"))
                && (contentType == null || !contentType.startsWith("multipart/"))
                && (accept == null || (!accept.contains("text/event-stream") && !accept.contains("application/octet-stream")));
    }

    private String safePath(String path) {
        return path.matches("/api/tasks(?:/\\d+)?") || "/api/external/objects".equals(path)
                ? path : "[redacted path]";
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
