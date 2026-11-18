package com.erlan.testtask.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
@SpringBootTest(properties = {
        "app.mail.enabled=true",
        "spring.cache.type=redis",
        "spring.datasource.url=jdbc:h2:mem:redis-email-tests;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@AutoConfigureMockMvc
class RedisEmailIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TaskRepository repository;
    @Autowired private CacheManager cacheManager;

    @MockitoBean
    private JavaMailSender mailSender;

    @Test
    void smtpFailureDoesNotLeaveRedisStale() throws Exception {
        repository.deleteAll();
        var cache = cacheManager.getCache(CacheConfig.TASKS_CACHE);
        cache.clear();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(cache.get(SimpleKey.EMPTY)).isNotNull();
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        var result = mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Created\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertThat(repository.existsById(id)).isTrue();
        assertThat(cache.get(SimpleKey.EMPTY)).isNull();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
