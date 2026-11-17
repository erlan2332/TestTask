package com.erlan.testtask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.exception.GlobalExceptionHandler;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.logging.HttpLoggingFilter;
import com.erlan.testtask.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(TaskController.class)
@Import({GlobalExceptionHandler.class, HttpLoggingFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class TaskErrorAndLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService service;

    @Test
    void missingTaskHasStructured404() throws Exception {
        when(service.getTaskById(999L)).thenThrow(new TaskNotFoundException(999L));

        assertError(mockMvc.perform(get("/api/tasks/999"))
                .andExpect(status().isNotFound()).andReturn(), 404, "Not Found",
                "Task with id 999 not found", "/api/tasks/999");
    }

    @Test
    void blankTitleHasStructured400() throws Exception {
        assertError(postJson("{\"title\":\"  \"}"), 400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void longTitleHasStructured400() throws Exception {
        assertError(postJson("{\"title\":\"" + "x".repeat(256) + "\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void longDescriptionHasStructured400() throws Exception {
        assertError(postJson("{\"title\":\"Valid\",\"description\":\"" + "x".repeat(2001) + "\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void invalidStatusHasStructured400() throws Exception {
        assertError(postJson("{\"title\":\"Valid\",\"status\":\"STARTED\"}"),
                400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void malformedJsonHasStructured400() throws Exception {
        assertError(postJson("{\"title\":"), 400, "Bad Request", "Invalid request", "/api/tasks");
    }

    @Test
    void invalidIdHasStructured400() throws Exception {
        assertError(mockMvc.perform(get("/api/tasks/not-an-id"))
                .andExpect(status().isBadRequest()).andReturn(),
                400, "Bad Request", "Invalid request", "/api/tasks/not-an-id");
    }

    @Test
    void unsupportedMethodHasStructured405() throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/tasks"))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertError(result, 405, "Method Not Allowed", "HTTP method not supported", "/api/tasks");
        assertThat(result.getResponse().getHeader("Allow")).isNotBlank();
    }

    @Test
    void unsupportedMediaTypeHasStructured415() throws Exception {
        assertError(mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andReturn(),
                415, "Unsupported Media Type", "Unsupported media type", "/api/tasks");
    }

    @Test
    void unexpectedErrorHasSafe500(CapturedOutput output) throws Exception {
        when(service.getAllTasks()).thenThrow(new IllegalStateException("database password: secret-500"));

        MvcResult result = mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertError(result, 500, "Internal Server Error", "Internal server error", "/api/tasks");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-500", "IllegalStateException", "stackTrace");
        assertThat(httpLogs(output)).contains("HTTP RESPONSE | GET /api/tasks | status=500 | durationMs=")
                .doesNotContain("secret-500");
    }

    @Test
    void logsRequestResponseAndPreservesJson(CapturedOutput output) throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        when(service.createTask(any())).thenReturn(new TaskResponse(1L, "private title", null,
                TaskStatus.TODO, now, now));
        String title = "title-secret-123";
        String body = "{\"title\":\"" + title + "\",\"status\":\"TODO\","
                + "\"password\":\"pw-123\",\"nested\":{\"apiKey\":\"key-123\"}}";

        MvcResult result = mockMvc.perform(post("/api/tasks?access_token=query-secret-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Basic auth-secret-123")
                        .header("Cookie", "session=cookie-secret-123")
                        .content(body))
                .andExpect(status().isCreated()).andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.path("id").asLong()).isEqualTo(1);
        assertThat(response.path("title").asText()).isEqualTo("private title");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/tasks/1");
        String logs = httpLogs(output);
        assertThat(logs).contains("HTTP REQUEST | POST /api/tasks | body=");
        assertThat(logs).contains("HTTP RESPONSE | POST /api/tasks | status=201 | durationMs=");
        assertThat(logs).contains("\"status\":\"TODO\"", "\"id\":1", "[REDACTED]");
        assertThat(logs).doesNotContain(title, "private title", "pw-123", "key-123", "auth-secret-123",
                "cookie-secret-123", "query-secret-123", "password", "apiKey");
    }

    @Test
    void logsErrorAndEmptyBodies(CapturedOutput output) throws Exception {
        when(service.getTaskById(999L)).thenThrow(new TaskNotFoundException(999L));

        mockMvc.perform(get("/api/tasks/999")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/tasks/1")).andExpect(status().isNoContent());

        String logs = httpLogs(output);
        assertThat(logs).contains("HTTP REQUEST | GET /api/tasks/999 | body=[empty]");
        assertThat(logs).contains("HTTP RESPONSE | GET /api/tasks/999 | status=404 | durationMs=");
        assertThat(logs).contains("HTTP RESPONSE | DELETE /api/tasks/1 | status=204 | durationMs=");
        assertThat(logs).contains("body=[empty]");
    }

    @Test
    void largeBodyIsNotWrittenToLog(CapturedOutput output) throws Exception {
        String secret = "large-secret-123";
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + secret + "x".repeat(9000) + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(httpLogs(output)).contains("[truncated: body exceeds capture limit]")
                .doesNotContain(secret);
    }

    @Test
    void largeResponseReachesClientWithoutBeingLogged(CapturedOutput output) throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        when(service.getAllTasks()).thenReturn(IntStream.rangeClosed(1, 200)
                .mapToObj(id -> new TaskResponse((long) id, "private-response-" + id, null,
                        TaskStatus.TODO, now, now)).toList());

        MvcResult result = mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk()).andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(200);
        assertThat(httpLogs(output)).contains("[truncated: body exceeds capture limit]")
                .doesNotContain("private-response-");
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

    private String httpLogs(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("HTTP REQUEST |")
                || line.contains("HTTP RESPONSE |")).reduce("", (left, right) -> left + right + "\n");
    }
}
