package com.erlan.testtask.service;

import com.erlan.testtask.event.TaskCreatedEvent;

public interface EmailService {

    void sendTaskCreatedEmail(TaskCreatedEvent event);
}
