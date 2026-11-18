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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void returnsOriginalJsonAndLogsItOnce(CapturedOutput output) {
        String json = "[{\"id\":\"1\",\"name\":\"Pixel\",\"data\":{\"capacity\":\"128 GB\"}},"
                + "{\"id\":\"2\",\"name\":\"Empty\",\"data\":null}]";
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        JsonNode result = service.getObjects();

        assertThat(result.get(0).path("data").path("capacity").asText()).isEqualTo("128 GB");
        assertThat(result.get(1).path("data").isNull()).isTrue();
        String log = output.getOut().lines().filter(line -> line.contains("EXTERNAL API | GET"))
                .findFirst().orElseThrow();
        assertThat(log).contains("EXTERNAL API | GET " + URL + " | status=200 | response=" + json)
                .doesNotContain("[REDACTED]", "omittedItems");
        assertThat(output.getOut().lines().filter(line -> line.contains("EXTERNAL API | GET")).count()).isEqualTo(1);
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

    @ParameterizedTest
    @ValueSource(strings = {"", "[{", "{\"id\":\"1\"}", "[1,2]"})
    void rejectsInvalidJsonResponses(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::getObjects).isInstanceOf(ExternalServiceException.class)
                .hasMessage("External service returned invalid data");
        server.verify();
    }

    @Test
    void oversizedResponseIsReturnedWholeAndLogIsMarkedTruncated(CapturedOutput output) {
        String json = "[{\"id\":\"1\",\"name\":\"" + "x".repeat(70_000) + "\",\"data\":null}]";
        server.expect(requestTo(URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        JsonNode result = service.getObjects();

        assertThat(result.get(0).path("name").asText()).hasSize(70_000);
        String log = output.getOut().lines().filter(line -> line.contains("EXTERNAL API | GET"))
                .findFirst().orElseThrow();
        assertThat(log).contains("status=200 | response=[{\"id\":\"1\",\"name\":\"", "... [truncated]")
                .doesNotContain("x".repeat(70_000), "omittedItems", "[REDACTED]");
        server.verify();
    }
}
