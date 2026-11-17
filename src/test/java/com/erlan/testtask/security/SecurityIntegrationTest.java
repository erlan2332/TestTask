package com.erlan.testtask.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.ExternalApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository repository;

    @MockitoBean
    private ExternalApiService externalApiService;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void everyApiOperationRequiresAuthentication() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/api/tasks")).andReturn(), "/api/tasks");
        assertUnauthorized(mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Task\"}")).andReturn(), "/api/tasks");
        assertUnauthorized(mockMvc.perform(get("/api/tasks/1")).andReturn(), "/api/tasks/1");
        assertUnauthorized(mockMvc.perform(put("/api/tasks/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Task\",\"status\":\"TODO\"}")).andReturn(), "/api/tasks/1");
        assertUnauthorized(mockMvc.perform(delete("/api/tasks/1")).andReturn(), "/api/tasks/1");
        assertUnauthorized(mockMvc.perform(get("/api/external/objects")).andReturn(), "/api/external/objects");
        verifyNoInteractions(externalApiService);
        assertThat(repository.count()).isZero();
    }

    @Test
    void wrongUsernameAndPasswordHaveSameSafeResponse() throws Exception {
        MvcResult wrongUsername = mockMvc.perform(get("/api/tasks")
                .with(httpBasic("wrong-user", "test-password"))).andReturn();
        MvcResult wrongPassword = mockMvc.perform(get("/api/tasks")
                .with(httpBasic("test-user", "wrong-password"))).andReturn();

        assertUnauthorized(wrongUsername, "/api/tasks");
        assertUnauthorized(wrongPassword, "/api/tasks");
        JsonNode first = objectMapper.readTree(wrongUsername.getResponse().getContentAsString());
        JsonNode second = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        assertThat(first.path("message")).isEqualTo(second.path("message"));
        assertThat(first.path("error")).isEqualTo(second.path("error"));
    }

    @Test
    void authenticatedCrudWorksWithoutSession() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Task\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(created.getRequest().getSession(false)).isNull();
        assertThat(created.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Task"));
        mockMvc.perform(put("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mockMvc.perform(delete("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNoContent());
        assertThat(repository.count()).isZero();
    }

    @Test
    void authenticatedBusinessErrorsKeepTheirStatuses() throws Exception {
        mockMvc.perform(get("/api/tasks/99999").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
        mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void credentialsNeverAppearInHttpLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "wrong-password")))
                .andExpect(status().isUnauthorized());

        String encoded = Base64.getEncoder().encodeToString("test-user:test-password"
                .getBytes(StandardCharsets.UTF_8));
        String logs = output.getOut();
        assertThat(logs).contains("HTTP RESPONSE | GET /api/tasks | status=200 |")
                .contains("HTTP RESPONSE | GET /api/tasks | status=401 |");
        assertThat(logs).doesNotContain("Authorization", encoded, "test-password", "wrong-password");
        assertThat(logs.lines().filter(line -> line.contains("HTTP REQUEST | GET /api/tasks |")).count())
                .isEqualTo(2);
        assertThat(logs.lines().filter(line -> line.contains("HTTP RESPONSE | GET /api/tasks |")).count())
                .isEqualTo(2);
    }

    private void assertUnauthorized(MvcResult result, String path) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic ");
        assertThat(result.getRequest().getSession(false)).isNull();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("timestamp").asText()).isNotBlank();
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("error").asText()).isEqualTo("Unauthorized");
        assertThat(body.path("message").asText()).isEqualTo("Authentication required");
        assertThat(body.path("path").asText()).isEqualTo(path);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("password", "Exception", "stackTrace");
    }
}
