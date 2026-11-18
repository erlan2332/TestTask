package com.erlan.testtask.service.impl;

import com.erlan.testtask.exception.ExternalServiceException;
import com.erlan.testtask.service.ExternalApiService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExternalApiServiceImpl implements ExternalApiService {

    private static final String API_NAME = "https://api.restful-api.dev/objects";
    private static final int MAX_LOG_CHARS = 65_536;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public JsonNode getObjects() {
        ResponseEntity<String> response;
        try {
            response = restClient.get().uri("").accept(MediaType.APPLICATION_JSON).retrieve().toEntity(String.class);
        } catch (RestClientResponseException ex) {
            log.warn("EXTERNAL API | GET {} | upstreamStatus={}", API_NAME, ex.getStatusCode().value());
            throw new ExternalServiceException("External service request failed", ex);
        } catch (RestClientException ex) {
            log.warn("EXTERNAL API | GET {} | request failed", API_NAME);
            throw new ExternalServiceException("External service unavailable", ex);
        }

        String body = response.getBody();
        JsonNode objects = parseResponse(body);
        String loggedBody = body.length() <= MAX_LOG_CHARS
                ? body
                : body.substring(0, MAX_LOG_CHARS) + "... [truncated]";
        log.info("EXTERNAL API | GET {} | status={} | response={}",
                API_NAME, response.getStatusCode().value(), loggedBody);
        return objects;
    }

    private JsonNode parseResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new ExternalServiceException("External service returned invalid data");
        }
        try {
            JsonNode objects = objectMapper.readTree(body);
            if (objects == null || !objects.isArray()) {
                throw new ExternalServiceException("External service returned invalid data");
            }
            for (JsonNode object : objects) {
                if (!object.isObject()) {
                    throw new ExternalServiceException("External service returned invalid data");
                }
            }
            return objects;
        } catch (JsonProcessingException ex) {
            throw new ExternalServiceException("External service returned invalid data", ex);
        }
    }
}
