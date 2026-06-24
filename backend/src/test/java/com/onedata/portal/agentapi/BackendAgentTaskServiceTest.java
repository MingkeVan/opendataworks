package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.service.BackendAgentTaskService;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.service.DataTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
