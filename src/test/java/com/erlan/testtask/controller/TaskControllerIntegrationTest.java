package com.erlan.testtask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final TaskRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    TaskControllerIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            TaskRepository repository,
            JdbcTemplate jdbcTemplate
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void postCreatesTaskInH2WithDefaultStatusAndTimestamps() throws Exception {
        MockHttpServletResponse httpResponse = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Write tests","description":"For CRUD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Write tests"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn().getResponse();

        JsonNode body = objectMapper.readTree(httpResponse.getContentAsString());
        long id = body.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(httpResponse.getHeader("Location")).isEqualTo("/api/tasks/" + id);
        assertThat(Instant.parse(body.path("createdAt").asText())).isNotNull();
        assertThat(Instant.parse(body.path("updatedAt").asText())).isNotNull();

        Task stored = repository.findById(id).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Write tests");
        assertThat(stored.getDescription()).isEqualTo("For CRUD");
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(stored.getCreatedAt()).isEqualTo(Instant.parse(body.path("createdAt").asText()));
        assertThat(stored.getUpdatedAt()).isEqualTo(Instant.parse(body.path("updatedAt").asText()));
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'TASKS'",
                Integer.class
        );
        assertThat(tableCount).isEqualTo(1);
    }

    @Test
    void getReturnsCreatedTask() throws Exception {
        Task stored = repository.saveAndFlush(new Task("Read me", "Details", TaskStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/tasks/{id}", stored.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stored.getId()))
                .andExpect(jsonPath("$.title").value("Read me"))
                .andExpect(jsonPath("$.description").value("Details"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void getAllReturnsTasks() throws Exception {
        repository.saveAndFlush(new Task("First", null, TaskStatus.TODO));
        repository.saveAndFlush(new Task("Second", null, TaskStatus.DONE));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", containsInAnyOrder("First", "Second")));
    }

    @Test
    void getAllReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getMissingTaskReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", 99999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void putUpdatesTaskAndTimestamps() throws Exception {
        Task stored = repository.saveAndFlush(new Task("Old", "To clear", TaskStatus.TODO));
        Instant createdAt = stored.getCreatedAt();
        Instant oldUpdatedAt = stored.getUpdatedAt();
        Thread.sleep(10);

        MockHttpServletResponse httpResponse = mockMvc.perform(put("/api/tasks/{id}", stored.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"New","description":null,"status":"DONE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"))
                .andExpect(jsonPath("$.description").value((Object) null))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andReturn().getResponse();

        JsonNode body = objectMapper.readTree(httpResponse.getContentAsString());
        assertThat(Instant.parse(body.path("createdAt").asText())).isEqualTo(createdAt);
        assertThat(Instant.parse(body.path("updatedAt").asText())).isAfter(oldUpdatedAt);

        Task updated = repository.findById(stored.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("New");
        assertThat(updated.getDescription()).isNull();
        assertThat(updated.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(oldUpdatedAt);
        assertThat(updated.getUpdatedAt()).isEqualTo(Instant.parse(body.path("updatedAt").asText()));
    }

    @Test
    void deleteRemovesTask() throws Exception {
        Task stored = repository.saveAndFlush(new Task("Delete me", null, TaskStatus.TODO));

        mockMvc.perform(delete("/api/tasks/{id}", stored.getId()))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

        assertThat(repository.existsById(stored.getId())).isFalse();
    }

    @Test
    void invalidRequestsReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  ","status":"TODO"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateTaskRequest("x".repeat(256), null, null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateTaskRequest("Valid", "x".repeat(2001), null))))
                .andExpect(status().isBadRequest());

        Task stored = repository.saveAndFlush(new Task("Original", null, TaskStatus.TODO));
        mockMvc.perform(put("/api/tasks/{id}", stored.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Changed","status":null}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.findById(stored.getId()).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void invalidEnumReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Invalid status","status":"STARTED"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void updateAndDeleteMissingTasksReturnNotFound() throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", 99999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Missing","status":"TODO"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/tasks/{id}", 99999L))
                .andExpect(status().isNotFound());

        assertThat(repository.count()).isZero();
    }
}
