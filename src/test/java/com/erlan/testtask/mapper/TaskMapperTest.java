package com.erlan.testtask.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.erlan.testtask.dto.request.CreateTaskRequest;
import com.erlan.testtask.dto.request.UpdateTaskRequest;
import com.erlan.testtask.dto.response.TaskResponse;
import com.erlan.testtask.entity.Task;
import com.erlan.testtask.enums.TaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TaskMapperTest {

    private final TaskMapper mapper = new TaskMapper();

    @Test
    void toEntityCopiesEditableFields() {
        Task task = mapper.toEntity(new CreateTaskRequest("Title", "Description", TaskStatus.IN_PROGRESS));

        assertThat(task.getTitle()).isEqualTo("Title");
        assertThat(task.getDescription()).isEqualTo("Description");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getId()).isNull();
        assertThat(task.getCreatedAt()).isNull();
        assertThat(task.getUpdatedAt()).isNull();
    }

    @Test
    void toEntityDefaultsStatusToTodo() {
        Task task = mapper.toEntity(new CreateTaskRequest("Title", null, null));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void toDtoCopiesEveryField() {
        Task task = new Task("Title", "Description", TaskStatus.DONE);
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T00:00:00Z");
        ReflectionTestUtils.setField(task, "id", 42L);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", updatedAt);

        TaskResponse response = mapper.toDto(task);

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getTitle()).isEqualTo("Title");
        assertThat(response.getDescription()).isEqualTo("Description");
        assertThat(response.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateEntityChangesOnlyEditableFieldsAndClearsDescription() {
        Task task = new Task("Old", "To clear", TaskStatus.TODO);
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T00:00:00Z");
        ReflectionTestUtils.setField(task, "id", 42L);
        ReflectionTestUtils.setField(task, "createdAt", createdAt);
        ReflectionTestUtils.setField(task, "updatedAt", updatedAt);

        mapper.updateEntity(new UpdateTaskRequest("New", null, TaskStatus.DONE), task);

        assertThat(task.getTitle()).isEqualTo("New");
        assertThat(task.getDescription()).isNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getId()).isEqualTo(42L);
        assertThat(task.getCreatedAt()).isEqualTo(createdAt);
        assertThat(task.getUpdatedAt()).isEqualTo(updatedAt);
    }
}
