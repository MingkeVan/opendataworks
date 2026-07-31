package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.lineage.LineageValidationMode;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import com.onedata.portal.service.lineage.TaskLineageWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务更新时的血缘合并真值表。
 *
 * <p>省略 = 保留原值；{@code []} = 清空该侧；非空数组 = 全量替换该侧。
 * 输出侧合并后不允许为空。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataTaskServiceLineageMergeTest {

    private static final long TASK_ID = 7L;

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
    @Mock
    private TaskLineageWriteService taskLineageWriteService;
    @Mock
    private TaskLineageConsistencyChecker taskLineageConsistencyChecker;

    private DataTaskService service;

    @BeforeEach
    void setUp() {
        service = new DataTaskService(
                dataTaskMapper,
                dataLineageMapper,
                executionLogMapper,
                tableTaskRelationMapper,
                workflowTaskRelationMapper,
                dataWorkflowMapper,
                dolphinSchedulerService,
                dataQueryService,
                dorisClusterService,
                workflowService,
                taskLineageWriteService,
                taskLineageConsistencyChecker);

        DataTask existing = new DataTask();
        existing.setId(TASK_ID);
        existing.setTaskName("task-a");
        existing.setDolphinNodeType("SQL");
        when(dataTaskMapper.selectById(TASK_ID)).thenReturn(existing);
        // 库里既有血缘：输入 [1,2]，输出 [9]
        stubExistingLineage(Arrays.asList(1L, 2L), Collections.singletonList(9L));
        when(taskLineageConsistencyChecker.findHighConfidenceGap(any(), any(), any()))
                .thenReturn(new TaskLineageConsistencyChecker.HighConfidenceGap());
    }

    private void stubExistingLineage(List<Long> inputs, List<Long> outputs) {
        List<DataLineage> rows = new ArrayList<>();
        for (Long id : inputs) {
            DataLineage row = new DataLineage();
            row.setTaskId(TASK_ID);
            row.setUpstreamTableId(id);
            row.setLineageType("input");
            rows.add(row);
        }
        List<DataLineage> outputRows = new ArrayList<>();
        for (Long id : outputs) {
            DataLineage row = new DataLineage();
            row.setTaskId(TASK_ID);
            row.setDownstreamTableId(id);
            row.setLineageType("output");
            outputRows.add(row);
        }
        // getTaskLineage 先查 input 再查 output
        when(dataLineageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(rows, outputRows);
    }

    private DataTask incomingTask() {
        DataTask task = new DataTask();
        task.setId(TASK_ID);
        task.setTaskName("task-a");
        task.setDolphinNodeType("SQL");
        return task;
    }

    @SuppressWarnings("unchecked")
    private void assertPersistedLineage(List<Long> expectedInputs, List<Long> expectedOutputs) {
        ArgumentCaptor<List<Long>> inputs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Long>> outputs = ArgumentCaptor.forClass(List.class);
        verify(taskLineageWriteService).replaceTaskLineage(eq(TASK_ID), inputs.capture(), outputs.capture());
        assertEquals(expectedInputs, inputs.getValue());
        assertEquals(expectedOutputs, outputs.getValue());
    }

    @Test
    void omittingBothSidesKeepsLineageAndSkipsWriteEntirely() {
        service.update(incomingTask(), null, null);

        // 两侧都省略时不该产生任何血缘写操作，连删除重建都不做。
        verify(taskLineageWriteService, never()).replaceTaskLineage(any(), anyList(), anyList());
    }

    @Test
    void replacingOnlyInputKeepsExistingOutput() {
        // 回归：旧实现会用原始入参校验，outputTableIds=null 被判成"输出为空"直接抛错。
        service.update(incomingTask(), Arrays.asList(3L, 4L), null);

        assertPersistedLineage(Arrays.asList(3L, 4L), Collections.singletonList(9L));
    }

    @Test
    void replacingOnlyOutputKeepsExistingInput() {
        service.update(incomingTask(), null, Collections.singletonList(8L));

        assertPersistedLineage(Arrays.asList(1L, 2L), Collections.singletonList(8L));
    }

    @Test
    void explicitEmptyInputClearsInputSide() {
        service.update(incomingTask(), Collections.emptyList(), null);

        assertPersistedLineage(Collections.emptyList(), Collections.singletonList(9L));
    }

    @Test
    void explicitEmptyOutputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update(incomingTask(), null, Collections.emptyList()));
    }

    @Test
    void explicitEmptyOutputDoesNotTouchLineageBeforeFailing() {
        // 校验必须发生在删除之前，否则会留下"报错了但血缘已经没了"的中间态。
        assertThrows(IllegalArgumentException.class,
                () -> service.update(incomingTask(), null, Collections.emptyList()));

        verify(taskLineageWriteService, never()).replaceTaskLineage(any(), anyList(), anyList());
    }

    @Test
    void bothSidesProvidedReplacesBoth() {
        service.update(incomingTask(), Collections.singletonList(5L), Collections.singletonList(6L));

        assertPersistedLineage(Collections.singletonList(5L), Collections.singletonList(6L));
    }

    @Test
    void strictModeRejectsWhenSqlInferredTableIsMissing() {
        TaskLineageConsistencyChecker.HighConfidenceGap gap =
                new TaskLineageConsistencyChecker.HighConfidenceGap();
        gap.getMissingInputs().add("ods.orders(id=1)");
        when(taskLineageConsistencyChecker.findHighConfidenceGap(any(), any(), any())).thenReturn(gap);

        assertThrows(RuntimeException.class,
                () -> service.update(incomingTask(),
                        Collections.singletonList(3L),
                        null,
                        LineageValidationMode.STRICT));

        verify(taskLineageWriteService, never()).replaceTaskLineage(any(), anyList(), anyList());
    }

    @Test
    void lenientModeSkipsHighConfidenceCheck() {
        service.update(incomingTask(), Collections.singletonList(3L), null, LineageValidationMode.LENIENT);

        verify(taskLineageConsistencyChecker, never()).findHighConfidenceGap(any(), any(), any());
    }
}
