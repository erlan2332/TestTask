package com.erlan.testtask.service.impl;

import com.erlan.testtask.event.TaskCreatedEvent;
import com.erlan.testtask.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Value("${app.mail.to}")
    private String to;

    @Override
    public void sendTaskCreatedEmail(TaskCreatedEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("New task created: #" + event.getId());
        message.setText("Task ID: " + event.getId()
                + "\nTitle: " + event.getTitle()
                + "\nDescription: " + (event.getDescription() == null ? "Not provided" : event.getDescription())
                + "\nStatus: " + event.getStatus()
                + "\nCreated at: " + event.getCreatedAt());
        mailSender.send(message);
    }
}
