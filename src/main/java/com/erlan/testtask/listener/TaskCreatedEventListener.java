package com.erlan.testtask.listener;

import com.erlan.testtask.event.TaskCreatedEvent;
import com.erlan.testtask.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class TaskCreatedEventListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        try {
            emailService.sendTaskCreatedEmail(event);
            log.info("Task creation email sent for taskId={}", event.getId());
        } catch (MailException exception) {
            log.warn("Task creation email failed for taskId={}, errorType={}",
                    event.getId(), exception.getClass().getSimpleName());
        }
    }
}
