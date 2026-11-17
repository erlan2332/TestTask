package com.erlan.testtask.dto.request;

import com.erlan.testtask.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255)
        String title,

        @Size(max = 2000)
        String description,

        TaskStatus status
) {
}
