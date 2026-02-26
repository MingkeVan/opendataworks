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

import java.util.Arrays;
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

    @Test
    void createShouldIgnoreNullRowsWhenAllocatingNextTaskCode() {
        DataTask input = new DataTask();
        input.setTaskName("task-create-null-row");
        input.setTaskCode("task-create-null-row-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("SQL");
        input.setDatasourceName("ds_main");
        input.setDatasourceType("MYSQL");
        input.setTaskSql("insert into dwd.t1 select * from ods.t1");
        input.setOwner("tester");

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.insert(any())).thenAnswer(invocation -> {
            DataTask task = invocation.getArgument(0);
            task.setId(101L);
            return 1;
        });

        DataTask persisted = new DataTask();
        persisted.setId(101L);
        persisted.setTaskName("task-create-null-row");
        persisted.setTaskCode("task-create-null-row-code");
        persisted.setEngine("dolphin");
        persisted.setDolphinNodeType("SQL");
        persisted.setDatasourceName("ds_main");
        persisted.setDatasourceType("MYSQL");
        persisted.setTaskSql("insert into dwd.t1 select * from ods.t1");
        persisted.setOwner("tester");
        when(dataTaskMapper.selectById(101L)).thenReturn(persisted);

        DataTask codeRecord = new DataTask();
        codeRecord.setDolphinTaskCode(1001L);
        when(dataTaskMapper.selectList(any())).thenReturn(Arrays.asList(null, codeRecord));
        when(dolphinSchedulerService.nextTaskCode()).thenReturn(2002L);

        DataTask result = dataTaskService.create(input, Collections.singletonList(11L), Collections.singletonList(12L));

        assertNotNull(result);
        assertEquals(101L, result.getId());
        verify(dolphinSchedulerService).alignSequenceWithExistingTasks(Collections.singletonList(1001L));
        verify(dolphinSchedulerService).nextTaskCode();
    }
}
