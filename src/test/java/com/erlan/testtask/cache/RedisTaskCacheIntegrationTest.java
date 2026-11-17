package com.erlan.testtask.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.cache.type=redis",
        "spring.datasource.url=jdbc:h2:mem:redis-cache-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class RedisTaskCacheIntegrationTest {

    @Autowired
    private TaskService service;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void redisRoundTripPreservesDtosAndFiveMinuteTtl() {
        resetState();
        Task stored = repository.saveAndFlush(new Task("From H2", "Details", TaskStatus.IN_PROGRESS));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<TaskResponse> first = service.getAllTasks();
        long statementsAfterMiss = statistics.getPrepareStatementCount();
        List<TaskResponse> second = service.getAllTasks();

        assertThat(statementsAfterMiss).isPositive();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(statementsAfterMiss);
        assertThat(second).extracting(TaskResponse::getId).containsExactly(stored.getId());

        Set<String> keys = redis.keys("tasks::*");
        assertThat(keys).hasSize(1);
        Long ttl = redis.getExpire(keys.iterator().next(), TimeUnit.SECONDS);
        assertThat(ttl).isBetween(1L, 300L);

        Object cached = taskCache().get(SimpleKey.EMPTY).get();
        assertThat(cached).isInstanceOf(List.class);
        TaskResponse restored = (TaskResponse) ((List<?>) cached).get(0);
        assertThat(restored.getId()).isEqualTo(stored.getId());
        assertThat(restored.getTitle()).isEqualTo(first.get(0).getTitle());
        assertThat(restored.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(restored.getCreatedAt()).isEqualTo(stored.getCreatedAt()).isInstanceOf(Instant.class);
        assertThat(restored.getUpdatedAt()).isEqualTo(stored.getUpdatedAt()).isInstanceOf(Instant.class);
    }

    @Test
    void authorizedHttpCrudInvalidatesRedisAndAnonymousRequestsCannotReadIt() throws Exception {
        resetState();
        mockMvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        assertThat(redis.keys("tasks::*")).isEmpty();

        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(redis.keys("tasks::*")).hasSize(1);

        var created = mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Created\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(redis.keys("tasks::*")).isEmpty();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Created"));

        mockMvc.perform(put("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk());
        assertThat(redis.keys("tasks::*")).isEmpty();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Updated"));

        mockMvc.perform(delete("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNoContent());
        assertThat(redis.keys("tasks::*")).isEmpty();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(repository.count()).isZero();
        assertThat(redis.keys("tasks::*")).hasSize(1);
    }

    @Test
    void rollbackKeepsCachedListAndDatabaseInSync() {
        resetState();
        Task stored = repository.saveAndFlush(new Task("Before", null, TaskStatus.TODO));
        service.getAllTasks();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.updateTask(stored.getId(), new UpdateTaskRequest("Rolled back", null, TaskStatus.DONE));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(taskCache().get(SimpleKey.EMPTY)).isNotNull();
        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("Before");
        assertThat(repository.findById(stored.getId()).orElseThrow().getTitle()).isEqualTo("Before");
    }

    @Test
    void rejectedWritesDoNotEvictRedis() throws Exception {
        resetState();
        repository.saveAndFlush(new Task("Existing", null, TaskStatus.TODO));
        service.getAllTasks();
        assertThat(redis.keys("tasks::*")).hasSize(1);

        mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/tasks/99999").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Missing\",\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/tasks/99999").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNotFound());

        assertThat(redis.keys("tasks::*")).hasSize(1);
        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("Existing");
    }

    private Cache taskCache() {
        return cacheManager.getCache(CacheConfig.TASKS_CACHE);
    }

    private void resetState() {
        repository.deleteAll();
        taskCache().clear();
    }

}
