package com.erlan.testtask.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.mail.enabled=true",
        "app.mail.password=test-smtp-secret",
        "spring.cache.type=simple"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TaskEmailIntegrationTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository repository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CacheManager cacheManager;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void resetState() {
        repository.deleteAll();
        cacheManager.getCache(CacheConfig.TASKS_CACHE).clear();
        reset(mailSender);
    }

    @Test
    void emailIsSentAfterCommitAndNotBefore() {
        transactionTemplate.executeWithoutResult(status -> {
            taskService.createTask(new CreateTaskRequest("Committed", null, TaskStatus.TODO));
            verifyNoInteractions(mailSender);
        });

        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rollbackDoesNotSendEmailOrStoreTask() {
        transactionTemplate.executeWithoutResult(status -> {
            taskService.createTask(new CreateTaskRequest("Rolled back", null, TaskStatus.TODO));
            verifyNoInteractions(mailSender);
            status.setRollbackOnly();
        });

        verifyNoInteractions(mailSender);
        assertThat(repository.count()).isZero();
    }

    @Test
    void smtpFailureKeepsHttp201DatabaseAndFreshList(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        assertThat(cacheManager.getCache(CacheConfig.TASKS_CACHE).get(SimpleKey.EMPTY)).isNotNull();
        doThrow(new MailSendException("test-smtp-secret: server unavailable"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        var result = mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Created despite SMTP failure\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();

        assertThat(repository.existsById(id)).isTrue();
        assertThat(cacheManager.getCache(CacheConfig.TASKS_CACHE).get(SimpleKey.EMPTY)).isNull();
        mockMvc.perform(get("/api/tasks").with(httpBasic("test-user", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(output.getOut()).contains("Task creation email failed for taskId=" + id)
                .doesNotContain("test-smtp-secret", "server unavailable");
    }

    @Test
    void rejectedPostsAndOtherCrudDoNotSendEmail() throws Exception {
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Unauthorized\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "wrong-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Wrong password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailSender);
        assertThat(repository.count()).isZero();

        var result = mockMvc.perform(post("/api/tasks").with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Valid\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        verify(mailSender).send(any(SimpleMailMessage.class));
        reset(mailSender);

        mockMvc.perform(put("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated\",\"status\":\"DONE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/tasks/{id}", id).with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNoContent());
        verifyNoInteractions(mailSender);
    }
}
