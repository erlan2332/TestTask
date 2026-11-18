package com.erlan.testtask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.exception.ExternalServiceException;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.service.ExternalApiService;
import com.erlan.testtask.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser(username = "test-user")
class TaskErrorAndLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService service;

    @MockitoBean
    private ExternalApiService externalApiService;

    @Test
    void validationRejectsBlankAndOversizedFields() throws Exception {
        assertError(postJson("{\"title\":\"  \"}"), 400, "Bad Request", "Invalid request", "/api/tasks");
        assertError(postJson("{\"title\":\"" + "x".repeat(256) + "\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
        assertError(postJson("{\"title\":\"Valid\",\"description\":\"" + "x".repeat(2001) + "\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void invalidStatusAndMalformedJsonReturn400() throws Exception {
        assertError(postJson("{\"title\":\"Task\",\"status\":\"INVALID\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
        assertError(postJson("{\"title\":"), 400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void invalidIdAndUnsupportedMethodOrMediaTypeKeepHttpStatuses() throws Exception {
        assertError(mockMvc.perform(get("/api/tasks/not-an-id"))
                .andExpect(status().isBadRequest()).andReturn(),
                400, "Bad Request", "Invalid request", "/api/tasks/not-an-id");
        MvcResult method = mockMvc.perform(patch("/api/tasks"))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertError(method, 405, "Method Not Allowed", "HTTP method not supported", "/api/tasks");
        assertThat(method.getResponse().getHeader("Allow")).isNotBlank();
        assertError(mockMvc.perform(post("/api/tasks").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andReturn(),
                415, "Unsupported Media Type", "Unsupported media type", "/api/tasks");
    }

    @Test
    void unexpectedErrorHasSafe500AndIsLogged(CapturedOutput output) throws Exception {
        when(service.getTaskById(1L)).thenThrow(new IllegalStateException("database password: secret-500"));

        MvcResult result = mockMvc.perform(get("/api/tasks/1"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertError(result, 500, "Internal Server Error", "Internal server error", "/api/tasks/1");
        assertThat(responseLog(output).path("body"))
                .isEqualTo(objectMapper.readTree(result.getResponse().getContentAsString()));
        assertThat(output.getOut()).doesNotContain("secret-500", "IllegalStateException");
    }

    @Test
    void postLogsFullJsonWithoutHeadersQueriesOrCredentialFields(CapturedOutput output) throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        when(service.createTask(any())).thenReturn(new TaskResponse(1L, "Visible title", "Visible description",
                TaskStatus.TODO, now, now));
        String body = "{\"title\":\"Visible title\",\"description\":\"Visible description\","
                + "\"status\":\"TODO\",\"password\":\"body-secret\","
                + "\"nested\":{\"token\":\"nested-secret\"}}";

        MvcResult result = mockMvc.perform(post("/api/tasks?access_token=query-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(httpBasic("test-user", "test-password"))
                        .header("Cookie", "session=cookie-secret")
                        .header("X-Api-Key", "header-secret")
                        .content(body))
                .andExpect(status().isCreated()).andReturn();

        List<JsonNode> logs = httpLogs(output);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).path("method").asText()).isEqualTo("POST");
        assertThat(logs.get(0).path("path").asText()).isEqualTo("/api/tasks");
        assertThat(logs.get(0).path("uri").asText()).doesNotContain("?");
        assertThat(logs.get(0).path("body").path("title").asText()).isEqualTo("Visible title");
        assertThat(logs.get(0).path("body").path("description").asText()).isEqualTo("Visible description");
        assertThat(logs.get(0).path("body").path("status").asText()).isEqualTo("TODO");
        assertThat(logs.get(1).path("status").asInt()).isEqualTo(201);
        assertThat(logs.get(1).path("duration").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(logs.get(1).path("body"))
                .isEqualTo(objectMapper.readTree(result.getResponse().getContentAsString()));
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/tasks/1");
        String encoded = Base64.getEncoder().encodeToString(
                "test-user:test-password".getBytes(StandardCharsets.UTF_8));
        assertThat(logs.toString()).doesNotContain("body-secret", "nested-secret", "query-secret",
                "cookie-secret", "header-secret", encoded, "test-password", "Authorization", "Cookie");
    }

    @Test
    void putAndGetByIdLogFullJsonAndPreserveResponses(CapturedOutput output) throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        TaskResponse task = new TaskResponse(7L, "Updated title", "Updated description", TaskStatus.DONE, now, now);
        when(service.updateTask(any(), any())).thenReturn(task);
        when(service.getTaskById(7L)).thenReturn(task);
        String request = "{\"title\":\"Updated title\",\"description\":\"Updated description\","
                + "\"status\":\"DONE\"}";

        MvcResult updated = mockMvc.perform(put("/api/tasks/7")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andReturn();
        MvcResult fetched = mockMvc.perform(get("/api/tasks/7"))
                .andExpect(status().isOk()).andReturn();

        List<JsonNode> logs = httpLogs(output);
        assertThat(logs).hasSize(4);
        assertThat(logs.get(0).path("method").asText()).isEqualTo("PUT");
        assertThat(logs.get(0).path("body")).isEqualTo(objectMapper.readTree(request));
        assertThat(logs.get(1).path("body"))
                .isEqualTo(objectMapper.readTree(updated.getResponse().getContentAsString()));
        assertThat(logs.get(2).path("method").asText()).isEqualTo("GET");
        assertThat(logs.get(3).path("body"))
                .isEqualTo(objectMapper.readTree(fetched.getResponse().getContentAsString()));
        assertThat(logs.get(3).path("body").path("createdAt").asText()).isEqualTo(now.toString());
    }

    @Test
    void listLogsFullJsonAndDeleteKeepsEmptyResponse(CapturedOutput output) throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        when(service.getAllTasks()).thenReturn(List.of(
                new TaskResponse(1L, "First", null, TaskStatus.TODO, now, now),
                new TaskResponse(2L, "Second", "Details", TaskStatus.DONE, now, now)));

        MvcResult list = mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk()).andReturn();
        MvcResult deleted = mockMvc.perform(delete("/api/tasks/1"))
                .andExpect(status().isNoContent()).andReturn();

        List<JsonNode> logs = httpLogs(output);
        assertThat(logs).hasSize(4);
        assertThat(logs.get(0).path("method").asText()).isEqualTo("GET");
        assertThat(logs.get(1).path("body"))
                .isEqualTo(objectMapper.readTree(list.getResponse().getContentAsString()));
        assertThat(logs.get(1).path("body").get(1).path("description").asText()).isEqualTo("Details");
        assertThat(logs.get(2).path("method").asText()).isEqualTo("DELETE");
        assertThat(logs.get(3).path("status").asInt()).isEqualTo(204);
        assertThat(logs.get(3).has("body")).isFalse();
        assertThat(deleted.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void missingTaskLogs404WithFullErrorBody(CapturedOutput output) throws Exception {
        when(service.getTaskById(999L)).thenThrow(new TaskNotFoundException(999L));

        MvcResult result = mockMvc.perform(get("/api/tasks/999"))
                .andExpect(status().isNotFound()).andReturn();

        assertError(result, 404, "Not Found", "Task with id 999 not found", "/api/tasks/999");
        assertThat(httpLogs(output)).hasSize(2);
        assertThat(responseLog(output).path("body"))
                .isEqualTo(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    @Test
    void externalEndpointLogsMetadataFor200And502Only(CapturedOutput output) throws Exception {
        String json = "[{\"id\":\"1\",\"name\":\"Pixel\",\"data\":null}]";
        when(externalApiService.getObjects()).thenReturn(objectMapper.readTree(json))
                .thenThrow(new ExternalServiceException("External service unavailable"));

        MvcResult success = mockMvc.perform(get("/api/external/objects"))
                .andExpect(status().isOk()).andReturn();
        MvcResult failure = mockMvc.perform(get("/api/external/objects"))
                .andExpect(status().isBadGateway()).andReturn();

        assertThat(objectMapper.readTree(success.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(json));
        assertError(failure, 502, "Bad Gateway", "External service unavailable", "/api/external/objects");
        List<JsonNode> logs = httpLogs(output);
        assertThat(logs).hasSize(4);
        assertThat(logs.get(0).path("path").asText()).isEqualTo("/api/external/objects");
        assertThat(logs.get(1).path("status").asInt()).isEqualTo(200);
        assertThat(logs.get(3).path("status").asInt()).isEqualTo(502);
        assertThat(logs.stream().anyMatch(log -> log.has("body"))).isFalse();
        assertThat(logs.toString()).doesNotContain("Pixel");
    }

    @Test
    void unknownRouteLogsMetadataWithoutArbitraryBody(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/unknown?token=query-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privateField\":\"body-secret\"}"))
                .andExpect(status().isNotFound());

        List<JsonNode> logs = httpLogs(output);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).path("method").asText()).isEqualTo("POST");
        assertThat(logs.get(1).path("status").asInt()).isEqualTo(404);
        assertThat(logs.stream().anyMatch(log -> log.has("body"))).isFalse();
        assertThat(logs.toString()).doesNotContain("query-secret", "body-secret");
    }

    private MvcResult postJson(String body) throws Exception {
        return mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn();
    }

    private void assertError(MvcResult result, int status, String error, String message, String path) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(5);
        assertThat(Instant.parse(body.path("timestamp").asText())).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("error").asText()).isEqualTo(error);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.path("path").asText()).isEqualTo(path);
    }

    private JsonNode responseLog(CapturedOutput output) {
        return httpLogs(output).stream().filter(log -> "response".equals(log.path("type").asText()))
                .findFirst().orElseThrow();
    }

    private List<JsonNode> httpLogs(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("org.zalando.logbook.Logbook") && line.contains("{\"origin\""))
                .map(line -> {
                    try {
                        return objectMapper.readTree(line.substring(line.indexOf("{\"origin\"")));
                    } catch (Exception ex) {
                        throw new AssertionError("Invalid Logbook JSON", ex);
                    }
                }).toList();
    }
}
