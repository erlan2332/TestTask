package com.erlan.testtask.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.mapper.TaskMapper;
import com.erlan.testtask.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaskServiceImplTest {

    @Mock
    private TaskRepository repository;

    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskServiceImpl(repository, new TaskMapper());
    }

    @Test
    void createTaskSavesAndReturnsTask() {
        // Given
        CreateTaskRequest request = new CreateTaskRequest("Write tests", "CRUD tests", TaskStatus.IN_PROGRESS);
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 7L);
            return task;
        });

        // When
        TaskResponse response = service.createTask(request);

        // Then
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(repository).saveAndFlush(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTitle()).isEqualTo("Write tests");
        assertThat(taskCaptor.getValue().getDescription()).isEqualTo("CRUD tests");
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getTitle()).isEqualTo("Write tests");
        assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void createTaskDefaultsStatusToTodo() {
        // Given
        CreateTaskRequest request = new CreateTaskRequest("Write tests", null, null);
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        TaskResponse response = service.createTask(request);

        // Then
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(repository).saveAndFlush(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(response.getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void getAllTasksReturnsMappedTasks() {
        // Given
        when(repository.findAll()).thenReturn(List.of(
                task(1L, "First", TaskStatus.TODO),
                task(2L, "Second", TaskStatus.DONE)
        ));

        // When
        List<TaskResponse> responses = service.getAllTasks();

        // Then
        assertThat(responses).extracting(TaskResponse::getId).containsExactly(1L, 2L);
        assertThat(responses).extracting(TaskResponse::getStatus)
                .containsExactly(TaskStatus.TODO, TaskStatus.DONE);
        verify(repository).findAll();
    }

    @Test
    void getAllTasksReturnsEmptyList() {
        // Given
        when(repository.findAll()).thenReturn(List.of());

        // When
        List<TaskResponse> responses = service.getAllTasks();

        // Then
        assertThat(responses).isEmpty();
        verify(repository).findAll();
    }

    @Test
    void getTaskByIdReturnsMappedTask() {
        // Given
        when(repository.findById(4L)).thenReturn(Optional.of(task(4L, "Found", TaskStatus.IN_PROGRESS)));

        // When
        TaskResponse response = service.getTaskById(4L);

        // Then
        assertThat(response.getId()).isEqualTo(4L);
        assertThat(response.getTitle()).isEqualTo("Found");
        assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verify(repository).findById(4L);
    }

    @Test
    void getTaskByIdThrowsWhenMissing() {
        // Given
        when(repository.findById(99L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.getTaskById(99L))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("Task with id 99 not found");
        verify(repository).findById(99L);
    }

    @Test
    void updateTaskChangesAllEditableFields() {
        // Given
        Task existing = task(5L, "Old", TaskStatus.TODO);
        Instant createdAt = existing.getCreatedAt();
        UpdateTaskRequest request = new UpdateTaskRequest("New", null, TaskStatus.DONE);
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        // When
        TaskResponse response = service.updateTask(5L, request);

        // Then
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(repository).findById(5L);
        verify(repository).saveAndFlush(taskCaptor.capture());
        assertThat(taskCaptor.getValue()).isSameAs(existing);
        assertThat(taskCaptor.getValue().getTitle()).isEqualTo("New");
        assertThat(taskCaptor.getValue().getDescription()).isNull();
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(taskCaptor.getValue().getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.getTitle()).isEqualTo("New");
        assertThat(response.getDescription()).isNull();
        assertThat(response.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void updateTaskThrowsWhenMissing() {
        // Given
        when(repository.findById(99L)).thenReturn(Optional.empty());
        UpdateTaskRequest request = new UpdateTaskRequest("New", null, TaskStatus.DONE);

        // When / Then
        assertThatThrownBy(() -> service.updateTask(99L, request))
                .isInstanceOf(TaskNotFoundException.class);
        verify(repository).findById(99L);
        verify(repository, never()).saveAndFlush(any(Task.class));
        verify(repository, never()).delete(any(Task.class));
    }

    @Test
    void deleteTaskDeletesExistingTask() {
        // Given
        Task existing = task(6L, "Delete me", TaskStatus.TODO);
        when(repository.findById(6L)).thenReturn(Optional.of(existing));

        // When
        service.deleteTask(6L);

        // Then
        verify(repository).findById(6L);
        verify(repository).delete(existing);
    }

    @Test
    void deleteTaskThrowsWhenMissing() {
        // Given
        when(repository.findById(99L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> service.deleteTask(99L))
                .isInstanceOf(TaskNotFoundException.class);
        verify(repository).findById(99L);
        verify(repository, never()).delete(any(Task.class));
        verify(repository, never()).saveAndFlush(any(Task.class));
    }

    private Task task(Long id, String title, TaskStatus status) {
        Task task = new Task(title, "Description", status);
        ReflectionTestUtils.setField(task, "id", id);
        ReflectionTestUtils.setField(task, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(task, "updatedAt", Instant.parse("2026-01-01T00:00:00Z"));
        return task;
    }
}
