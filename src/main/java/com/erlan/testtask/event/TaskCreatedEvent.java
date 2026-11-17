package com.erlan.testtask.event;

import com.erlan.testtask.enums.TaskStatus;
import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TaskCreatedEvent {

    private final Long id;
    private final String title;
    private final String description;
    private final TaskStatus status;
    private final Instant createdAt;
}
