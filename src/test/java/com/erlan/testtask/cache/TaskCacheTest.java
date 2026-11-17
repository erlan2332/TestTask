package com.erlan.testtask.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erlan.testtask.config.CacheConfig;
import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import com.erlan.testtask.exception.TaskNotFoundException;
import com.erlan.testtask.repository.TaskRepository;
import com.erlan.testtask.service.TaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = "spring.cache.type=simple")
class TaskCacheTest {

    @Autowired
    private TaskService service;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private TaskRepository repository;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(CacheConfig.TASKS_CACHE).clear();
    }

    @Test
    void secondReadUsesCachedDtos() {
        when(repository.findAll()).thenReturn(List.of(task(1L, "First")));

        List<TaskResponse> first = service.getAllTasks();
        List<TaskResponse> second = service.getAllTasks();

        assertThat(first).extracting(TaskResponse::getTitle).containsExactly("First");
        assertThat(second).extracting(TaskResponse::getTitle).containsExactly("First");
        verify(repository).findAll();
    }

    @Test
    void emptyListIsCached() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.getAllTasks()).isEmpty();
        assertThat(service.getAllTasks()).isEmpty();

        verify(repository).findAll();
    }

    @Test
    void createEvictsTaskList() {
        when(repository.findAll()).thenReturn(List.of()).thenReturn(List.of(task(2L, "Created")));
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(invocation -> {
            Task saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 2L);
            return saved;
        });

        assertThat(service.getAllTasks()).isEmpty();
        service.createTask(new CreateTaskRequest("Created", null, TaskStatus.TODO));
        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("Created");
        verify(repository, times(2)).findAll();
    }

    @Test
    void updateEvictsTaskList() {
        Task original = task(1L, "Old");
        when(repository.findAll()).thenReturn(List.of(task(1L, "Old")))
                .thenReturn(List.of(task(1L, "New")));
        when(repository.findById(1L)).thenReturn(Optional.of(original));
        when(repository.saveAndFlush(original)).thenReturn(original);

        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("Old");
        service.updateTask(1L, new UpdateTaskRequest("New", null, TaskStatus.DONE));
        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("New");
        verify(repository, times(2)).findAll();
    }

    @Test
    void deleteEvictsTaskList() {
        Task original = task(1L, "Old");
        when(repository.findAll()).thenReturn(List.of(task(1L, "Old"))).thenReturn(List.of());
        when(repository.findById(1L)).thenReturn(Optional.of(original));

        assertThat(service.getAllTasks()).hasSize(1);
        service.deleteTask(1L);
        assertThat(service.getAllTasks()).isEmpty();
        verify(repository, times(2)).findAll();
        verify(repository).delete(original);
    }

    @Test
    void unsuccessfulWritesKeepCachedList() {
        when(repository.findAll()).thenReturn(List.of(task(1L, "Still here")));
        when(repository.saveAndFlush(any(Task.class))).thenThrow(new IllegalStateException("save failed"));

        assertThat(service.getAllTasks()).hasSize(1);
        assertThatThrownBy(() -> service.createTask(new CreateTaskRequest("New", null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.updateTask(999L,
                new UpdateTaskRequest("New", null, TaskStatus.DONE)))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.deleteTask(999L)).isInstanceOf(TaskNotFoundException.class);
        assertThat(service.getAllTasks()).extracting(TaskResponse::getTitle).containsExactly("Still here");
        verify(repository).findAll();
    }

    private Task task(Long id, String title) {
        Task task = new Task(title, null, TaskStatus.TODO);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }
}
