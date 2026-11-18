package com.erlan.testtask.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpBodySanitizer {

    static final int CAPTURE_LIMIT_BYTES = 8192;
    private static final int MAX_BODY_CHARS = 4096;
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper;

    public String sanitize(byte[] bytes, String contentType) {
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
}
