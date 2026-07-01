package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentTaskUpsertRequest;
import com.onedata.portal.agentapi.service.BackendAgentTaskService;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.service.DataTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackendAgentTaskServiceTest {

    @Mock
    private DataTaskService dataTaskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BackendAgentTaskService service() {
        return new BackendAgentTaskService(dataTaskService, objectMapper);
    }

    @Test
    void getTaskReturnsTaskWhenFound() {
        DataTask task = new DataTask();
        task.setId(7L);
        when(dataTaskService.getById(7L)).thenReturn(task);

        assertSame(task, service().getTask(7L));
    }

    @Test
    void getTaskThrowsWhenMissingSoResponseStaysJson() {
        // A null task would otherwise serialize as an empty body, which the
        // portal MCP client rejects as "not valid JSON". The service must throw
        // so the global handler emits a proper JSON error instead.
        when(dataTaskService.getById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service().getTask(999L));
    }

    @Test
    void updateTaskPreservesExistingWorkflowBindingWhenOmitted() {
        DataTask existing = new DataTask();
        existing.setId(7L);
        existing.setWorkflowId(42L);
        when(dataTaskService.getById(7L)).thenReturn(existing);

        Map<String, Object> taskFields = new LinkedHashMap<>();
        taskFields.put("taskName", "renamed-task");
        AgentTaskUpsertRequest request = new AgentTaskUpsertRequest();
        request.setTask(taskFields);

        service().updateTask(7L, request, "agent-operator");

        ArgumentCaptor<DataTask> captor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService).update(captor.capture(), any(), any());
        assertEquals(42L, captor.getValue().getWorkflowId());
    }

    @Test
    void updateTaskHonorsExplicitWorkflowIdWhenProvided() {
        Map<String, Object> taskFields = new LinkedHashMap<>();
        taskFields.put("workflowId", null);
        AgentTaskUpsertRequest request = new AgentTaskUpsertRequest();
        request.setTask(taskFields);

        service().updateTask(7L, request, "agent-operator");

        ArgumentCaptor<DataTask> captor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService).update(captor.capture(), any(), any());
        assertNull(captor.getValue().getWorkflowId());
    }
}
