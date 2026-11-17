package com.erlan.testtask.service.impl;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.mapper.TaskMapper;
import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.TaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final TaskRepository repository;
    private final TaskMapper mapper;

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.TASKS_CACHE, allEntries = true)
    public TaskResponse createTask(CreateTaskRequest request) {
        Task task = mapper.toEntity(request);
        return mapper.toDto(repository.saveAndFlush(task));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.TASKS_CACHE)
    public List<TaskResponse> getAllTasks() {
        return repository.findAll().stream().map(mapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse getTaskById(Long id) {
        return mapper.toDto(findTask(id));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.TASKS_CACHE, allEntries = true)
    public TaskResponse updateTask(Long id, UpdateTaskRequest request) {
        Task task = findTask(id);
        mapper.updateEntity(request, task);
        return mapper.toDto(repository.saveAndFlush(task));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.TASKS_CACHE, allEntries = true)
    public void deleteTask(Long id) {
        repository.delete(findTask(id));
    }

    private Task findTask(Long id) {
        return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }
}
