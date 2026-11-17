package com.erlan.testtask.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class HttpLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_CHARS = 4096;
    private static final int CAPTURE_LIMIT_BYTES = 8192;
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper;

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

        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, CAPTURE_LIMIT_BYTES);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            try {
                log.info("HTTP REQUEST | {} {} | body={}", method, path,
                        safeBody(cachedRequest.getContentAsByteArray(), request.getContentType()));
                log.info("HTTP RESPONSE | {} {} | status={} | durationMs={} | body={}",
                        method, path, cachedResponse.getStatus(), elapsedMillis(start),
                        safeBody(cachedResponse.getContentAsByteArray(), cachedResponse.getContentType()));
            } finally {
                cachedResponse.copyBodyToResponse();
            }
        }
    }

    private boolean captureBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        String accept = request.getHeader("Accept");
        return request.getRequestURI().startsWith("/api/tasks")
                && (contentType == null || !contentType.startsWith("multipart/"))
                && (accept == null || (!accept.contains("text/event-stream") && !accept.contains("application/octet-stream")));
    }

    private String safeBody(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            return "[empty]";
        }
        if (!isJson(contentType)) {
            return "[omitted: non-JSON]";
        }
        if (bytes.length >= CAPTURE_LIMIT_BYTES) {
            return "[truncated: body exceeds capture limit]";
        }
        try {
            String result = objectMapper.writeValueAsString(redact(objectMapper.readTree(bytes)));
            return result.length() <= MAX_BODY_CHARS
                    ? result : result.substring(0, MAX_BODY_CHARS) + "...[truncated]";
        } catch (IOException ex) {
            return "[omitted: invalid JSON]";
        }
    }

    private boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.properties().forEach(field -> {
                String name = field.getKey();
                if (isKnownField(name)) {
                    result.set(name, safeValue(name, field.getValue()));
                } else {
                    result.put("[unknown field]", REDACTED);
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(redact(value)));
            return result;
        }
        return objectMapper.getNodeFactory().textNode(REDACTED);
    }

    private JsonNode safeValue(String name, JsonNode value) {
        if ("id".equals(name) && value.isIntegralNumber()) {
            return value;
        }
        if ("status".equals(name)) {
            if (value.isIntegralNumber()) {
                return value;
            }
            if (value.isTextual() && ("TODO".equals(value.textValue())
                    || "IN_PROGRESS".equals(value.textValue()) || "DONE".equals(value.textValue()))) {
                return value;
            }
        }
        if (value.isNull()) {
            return value;
        }
        return value.isContainerNode() ? redact(value) : objectMapper.getNodeFactory().textNode(REDACTED);
    }

    private boolean isKnownField(String name) {
        return switch (name) {
            case "id", "title", "description", "status", "createdAt", "updatedAt",
                    "timestamp", "error", "message", "path" -> true;
            default -> false;
        };
    }

    private String safePath(String path) {
        return path.matches("/api/tasks(?:/\\d+)?") || "/api/external/objects".equals(path)
                ? path : "[redacted path]";
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
