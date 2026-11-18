package com.erlan.testtask.mapper;

import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public Task toEntity(CreateTaskRequest request) {
        return new Task(request.getTitle(), request.getDescription(), request.getStatus());
    }

    public TaskResponse toDto(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    public void updateEntity(UpdateTaskRequest request, Task task) {
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setStatus(request.getStatus());
    }
}
