package com.erlan.testtask.dto.request;

import com.erlan.testtask.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskRequest(
        @NotBlank @Size(max = 255)
        String title,

        @Size(max = 2000)
        String description,

        @NotNull
        TaskStatus status
) {
}
