package com.erlan.testtask.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.erlan.testtask.exception.ExternalServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ExternalApiServiceImplTest {

    private static final String URL = "https://api.restful-api.dev/objects";

    private MockRestServiceServer server;
    private ExternalApiServiceImpl service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ExternalApiServiceImpl(builder.build(), new ObjectMapper());
    }

    @Test
    void getsJsonFromConfiguredUrlAndLogsActualResponse(CapturedOutput output) {
        String json = "[{\"id\":\"1\",\"name\":\"Pixel\",\"data\":{\"capacity\":\"128 GB\"}}]";
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        JsonNode result = service.getObjects();

        assertThat(result.isArray()).isTrue();
        assertThat(result.get(0).path("name").asText()).isEqualTo("Pixel");
        assertThat(result.get(0).path("data").path("capacity").asText()).isEqualTo("128 GB");
        assertThat(output.getOut()).contains("EXTERNAL API | GET restful-api.dev/objects | status=200 | response=" + json);
        server.verify();
    }

    @Test
    void mapsExternal500ToIntegrationError(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service request failed");
        assertThat(output.getOut()).contains("upstreamStatus=500");
        server.verify();
    }

    @Test
    void mapsExternal404ToIntegrationError(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service request failed");
        assertThat(output.getOut()).contains("upstreamStatus=404");
        server.verify();
    }

    @Test
    void mapsUnavailableServiceToIntegrationError(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service unavailable");
        assertThat(output.getOut()).contains("request failed").doesNotContain("connection refused");
        server.verify();
    }

    @Test
    void rejectsEmptyResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service returned invalid data");
        server.verify();
    }

    @Test
    void rejectsMalformedJson() {
        server.expect(requestTo(URL)).andRespond(withSuccess("[{", MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service returned invalid data");
        server.verify();
    }

    @Test
    void rejectsUnexpectedJsonStructure() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service returned invalid data");
        server.verify();
    }

    @Test
    void rejectsArrayWithoutObjects() {
        server.expect(requestTo(URL)).andRespond(withSuccess("[1, 2]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service returned invalid data");
        server.verify();
    }

    @Test
    void truncatesLargeLoggedResponseButReturnsFullJson(CapturedOutput output) {
        String json = "[{\"name\":\"" + "x".repeat(5000) + "\"}]";
        server.expect(requestTo(URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        JsonNode result = service.getObjects();

        assertThat(result.get(0).path("name").asText()).hasSize(5000);
        assertThat(output.getOut()).contains("...[truncated]").doesNotContain("x".repeat(5000));
        server.verify();
    }
}
