package com.erlan.testtask.dto.response;

import com.erlan.testtask.enums.TaskStatus;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TaskResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String title;
    private final String description;
    private final TaskStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
}
