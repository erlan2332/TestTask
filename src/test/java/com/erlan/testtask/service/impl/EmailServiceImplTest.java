package com.erlan.testtask.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.event.TaskCreatedEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmailServiceImpl(mailSender, "sender@example.test", "recipient@example.test");
    }

    @Test
    void sendsPlainTextTaskSnapshotToConfiguredAddresses() {
        TaskCreatedEvent event = new TaskCreatedEvent(123L, "Prepare assignment",
                "Complete Spring Boot project", TaskStatus.TODO, Instant.parse("2026-11-17T12:00:00Z"));

        service.sendTaskCreatedEmail(event);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("sender@example.test");
        assertThat(message.getTo()).containsExactly("recipient@example.test");
        assertThat(message.getSubject()).isEqualTo("New task created: #123");
        assertThat(message.getText()).contains("Task ID: 123", "Title: Prepare assignment",
                "Description: Complete Spring Boot project", "Status: TODO",
                "Created at: 2026-11-17T12:00:00Z");
    }

    @Test
    void missingDescriptionIsReadable() {
        service.sendTaskCreatedEmail(new TaskCreatedEvent(124L, "Short", null,
                TaskStatus.DONE, Instant.parse("2026-11-17T12:01:00Z")));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("Description: Not provided").doesNotContain("null");
    }
}
