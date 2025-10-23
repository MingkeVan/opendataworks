package com.onedata.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutionServiceTest {

    @Mock
    private TaskExecutionLogMapper executionLogMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    private TaskExecutionService service;

    @BeforeEach
    void setUp() {
        service = new TaskExecutionService(executionLogMapper, dataTaskMapper, dolphinSchedulerService);
    }

    @Test
    void getRecentExecutionsUsesDefaultLimitWhenNull() {
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.getRecentExecutions(1L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskExecutionLog>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(executionLogMapper).selectList(captor.capture());

        assertEquals("LIMIT 10", captor.getValue().getLastSql());
    }

    @Test
    void getRecentExecutionsFallbacksToDefaultLimitWhenNonPositive() {
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.getRecentExecutions(1L, -5);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskExecutionLog>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(executionLogMapper).selectList(captor.capture());

        assertEquals("LIMIT 10", captor.getValue().getLastSql());
    }

    @Test
    void getRecentExecutionsHonorsPositiveLimit() {
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.getRecentExecutions(1L, 5);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskExecutionLog>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(executionLogMapper).selectList(captor.capture());

        assertEquals("LIMIT 5", captor.getValue().getLastSql());
    }

    @Test
    void enrichWithDolphinDataBuildsWorkflowInstanceUrlFromDefinitionsBase() throws IOException {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setId(100L);
        log.setTaskId(200L);
        log.setExecutionId("exec-1");
        log.setStatus("running");

        when(executionLogMapper.selectList(any())).thenReturn(Collections.singletonList(log));

        DataTask task = new DataTask();
        task.setId(200L);
        task.setDolphinProcessCode(300L);
        task.setDolphinTaskCode(400L);
        when(dataTaskMapper.selectById(200L)).thenReturn(task);

        when(dolphinSchedulerService.getWorkflowName()).thenReturn("workflow");
        when(dolphinSchedulerService.getTaskDefinitionUrl(400L)).thenReturn("http://host/ui/projects/1/task/definitions/400");
        when(dolphinSchedulerService.getProjectCode()).thenReturn(1L);
        when(dolphinSchedulerService.getWorkflowDefinitionUrl(300L))
            .thenReturn("http://host/ui/projects/1/workflow/definitions/300");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode instanceDetail = mapper.readTree("{" +
            "\"instanceId\":1," +
            "\"state\":\"SUCCESS\"," +
            "\"startTime\":\"2024-01-01 00:00:00\"," +
            "\"endTime\":\"2024-01-01 00:10:00\"," +
            "\"duration\":600"
            + "}");
        when(dolphinSchedulerService.getWorkflowInstanceStatus(300L, "exec-1"))
            .thenReturn(instanceDetail);
        when(executionLogMapper.updateById(any())).thenReturn(1);

        List<TaskExecutionLog> result = service.getRecentExecutions(200L, 5);

        assertNotNull(result);
        assertEquals(1, result.size());
        TaskExecutionLog enriched = result.get(0);
        assertEquals("http://host/ui/projects/1/workflow/instance/300/exec-1", enriched.getWorkflowInstanceUrl());
        verify(dolphinSchedulerService).getWorkflowInstanceStatus(300L, "exec-1");
        verify(executionLogMapper).selectList(any());
        verify(executionLogMapper).updateById(any());
    }
}
