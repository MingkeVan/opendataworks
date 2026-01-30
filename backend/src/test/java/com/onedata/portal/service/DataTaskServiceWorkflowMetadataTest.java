package com.onedata.portal.service;

import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTaskServiceWorkflowMetadataTest {

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TaskExecutionLogMapper executionLogMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataQueryService dataQueryService;

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private DataTaskService dataTaskService;

    @Test
    void getByIdEnrichesWorkflowMetadata() {
        DataTask task = new DataTask();
        task.setId(1L);
        task.setTaskName("task-1");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setTaskId(1L);
        relation.setWorkflowId(10L);
        relation.setUpstreamTaskCount(2);
        relation.setDownstreamTaskCount(3);

        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(10L);
        workflow.setWorkflowName("workflow-10");

        when(dataTaskMapper.selectById(1L)).thenReturn(task);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));
        when(dataWorkflowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(workflow));

        DataTask result = dataTaskService.getById(1L);

        assertNotNull(result);
        assertEquals(10L, result.getWorkflowId());
        assertEquals("workflow-10", result.getWorkflowName());
        assertEquals(2, result.getUpstreamTaskCount());
        assertEquals(3, result.getDownstreamTaskCount());
    }

    @Test
    void updateClearingWorkflowIdRemovesRelationAndRefreshesPreviousWorkflow() {
        DataTask existing = new DataTask();
        existing.setId(1L);
        existing.setTaskName("existing");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setId(99L);
        relation.setTaskId(1L);
        relation.setWorkflowId(10L);

        DataTask updatePayload = new DataTask();
        updatePayload.setId(1L);
        updatePayload.setWorkflowId(null);

        when(dataTaskMapper.selectById(1L)).thenReturn(existing);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(relation);
        when(dataTaskMapper.updateById(updatePayload)).thenReturn(1);
        when(dataLineageMapper.delete(any())).thenReturn(0);
        when(tableTaskRelationMapper.hardDeleteByTaskId(1L)).thenReturn(1);

        dataTaskService.update(updatePayload, null, null);

        verify(workflowTaskRelationMapper).delete(any());
        verify(workflowTaskRelationMapper, never()).insert(any());
        verify(workflowTaskRelationMapper, never()).updateById(any());
        verify(workflowService).refreshTaskRelations(10L);
    }
}

