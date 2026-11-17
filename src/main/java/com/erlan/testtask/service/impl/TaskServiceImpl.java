package com.erlan.testtask.service.impl;

import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.TaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final TaskRepository repository;

    @Override
    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        TaskStatus status = request.status() == null ? TaskStatus.TODO : request.status();
        Task task = new Task(request.title(), request.description(), status);
        return toResponse(repository.saveAndFlush(task));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> getAllTasks() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse getTaskById(Long id) {
        return toResponse(findTask(id));
    }

    @Override
    @Transactional
    public TaskResponse updateTask(Long id, UpdateTaskRequest request) {
        Task task = findTask(id);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setStatus(request.status());
        return toResponse(repository.saveAndFlush(task));
    }

    @Override
    @Transactional
    public void deleteTask(Long id) {
        repository.delete(findTask(id));
    }

    private Task findTask(Long id) {
        return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private TaskResponse toResponse(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
