package com.onedata.portal.service.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairIssue;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.SqlTableMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskLineageConsistencyCheckerTest {

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;
    @Mock
    private DataTaskMapper dataTaskMapper;
    @Mock
    private DataWorkflowMapper dataWorkflowMapper;
    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    private LineageConsistencyProperties properties;
    private TaskLineageConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        properties = new LineageConsistencyProperties();
        checker = new TaskLineageConsistencyChecker(
                sqlTableMatcherService,
                dataTaskMapper,
                dataWorkflowMapper,
                tableTaskRelationMapper,
                workflowTaskRelationMapper,
                properties,
                new ObjectMapper());
    }

    private SqlTableAnalyzeResponse.TableRefMatch matched(String name, Long tableId) {
        SqlTableAnalyzeResponse.TableCandidate candidate = new SqlTableAnalyzeResponse.TableCandidate();
        candidate.setTableId(tableId);
        candidate.setTableName(name);
        SqlTableAnalyzeResponse.TableRefMatch ref = new SqlTableAnalyzeResponse.TableRefMatch();
        ref.setRawName(name);
        ref.setMatchStatus("matched");
        ref.setChosenTable(candidate);
        return ref;
    }

    private DataTask sqlTask(long id, String name) {
        DataTask task = new DataTask();
        task.setId(id);
        task.setTaskName(name);
        task.setDolphinNodeType("SQL");
        task.setTaskSql("INSERT INTO dws.t SELECT * FROM ods.s");
        task.setDolphinTaskCode(1000L + id);
        return task;
    }

    private void stubAnalyze(List<Long> inputIds, List<Long> outputIds,
            List<String> unmatched, List<String> ambiguous) {
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        List<SqlTableAnalyzeResponse.TableRefMatch> inputs = new ArrayList<>();
        for (Long id : inputIds) {
            inputs.add(matched("ods.s" + id, id));
        }
        List<SqlTableAnalyzeResponse.TableRefMatch> outputs = new ArrayList<>();
        for (Long id : outputIds) {
            outputs.add(matched("dws.t" + id, id));
        }
        analyze.setInputRefs(inputs);
        analyze.setOutputRefs(outputs);
        analyze.setUnmatched(unmatched);
        analyze.setAmbiguous(ambiguous);
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);
    }

    private void stubWorkflowTasks(DataWorkflow workflow, List<DataTask> tasks,
            List<TableTaskRelation> relations) {
        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        for (DataTask task : tasks) {
            WorkflowTaskRelation binding = new WorkflowTaskRelation();
            binding.setWorkflowId(workflow.getId());
            binding.setTaskId(task.getId());
            bindings.add(binding);
        }
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(tasks);
        when(tableTaskRelationMapper.selectList(any())).thenAnswer(invocation -> relations.stream()
                .filter(item -> "read".equals(item.getRelationType()))
                .collect(java.util.stream.Collectors.toList()));
    }

    private TableTaskRelation relation(long taskId, long tableId, String type) {
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(tableId);
        relation.setRelationType(type);
        return relation;
    }

    private DataWorkflow workflow() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(100L);
        workflow.setWorkflowName("wf");
        return workflow;
    }

    @Test
    void highConfidenceGapReportsMissingInputAndOutput() {
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.emptyList(), Collections.emptyList());

        assertFalse(gap.isEmpty());
        assertEquals(1, gap.getMissingInputs().size());
        assertEquals(1, gap.getMissingOutputs().size());
    }

    @Test
    void highConfidenceGapIsEmptyWhenLineageCoversSqlTables() {
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.singletonList(1L), Collections.singletonList(9L));

        assertTrue(gap.isEmpty());
    }

    @Test
    void extraLineageNeverBlocksSave() {
        // 多余血缘只在发布预检里告警，不参与保存校验，否则手工补充的依赖会导致任务存不进去。
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Arrays.asList(1L, 42L), Collections.singletonList(9L));

        assertTrue(gap.isEmpty());
    }

    @Test
    void nonSqlTaskSkipsSqlAnalysisEntirely() {
        DataTask shellTask = sqlTask(7L, "t");
        shellTask.setDolphinNodeType("SHELL");

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                shellTask, Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());
    }

    @Test
    void workflowCheckFlagsMissingRelationAsNonRepairable() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING.equals(issue.getCode())
                        && Boolean.FALSE.equals(issue.getRepairable())));
    }

    @Test
    void workflowCheckFlagsExtraRelationAsWarning() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task),
                Collections.singletonList(relation(7L, 42L, "read")));

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_RELATION_EXTRA.equals(issue.getCode())));
    }

    @Test
    void workflowCheckFlagsUnresolvedReferences() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList("ext.unknown_table"), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_SQL_UNRESOLVED.equals(issue.getCode())));
    }

    @Test
    void definitionDriftIsRepairableAndSkippedWhenNotRequested() {
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[1,2],\"outputTableIds\":[9]}]}");
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> withDrift = checker.checkWorkflow(workflow, true);
        assertTrue(withDrift.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                        && Boolean.TRUE.equals(issue.getRepairable())));

        // deploy 在 syncCurrentVersion() 之后调用时必须跳过漂移比对，
        // 否则会拦下一个 deploy 本来就会修好的工作流。
        List<WorkflowPublishRepairIssue> withoutDrift = checker.checkWorkflow(workflow, false);
        assertTrue(withoutDrift.stream().noneMatch(issue ->
                TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())));
    }

    @Test
    void warnModeNeverReportsBlockingIssues() {
        WorkflowPublishRepairIssue missing = new WorkflowPublishRepairIssue();
        missing.setCode(TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING);

        assertFalse(checker.hasBlockingIssue(Collections.singletonList(missing)));
    }

    @Test
    void blockMissingModeBlocksOnlyOnMissingRelation() {
        properties.setEnforcementMode(LineageConsistencyProperties.MODE_BLOCK_MISSING);

        WorkflowPublishRepairIssue missing = new WorkflowPublishRepairIssue();
        missing.setCode(TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING);
        WorkflowPublishRepairIssue unresolved = new WorkflowPublishRepairIssue();
        unresolved.setCode(TaskLineageConsistencyChecker.CODE_SQL_UNRESOLVED);
        WorkflowPublishRepairIssue extra = new WorkflowPublishRepairIssue();
        extra.setCode(TaskLineageConsistencyChecker.CODE_RELATION_EXTRA);

        assertTrue(checker.hasBlockingIssue(Collections.singletonList(missing)));
        // unmatched / ambiguous / 多余关系在任何模式下都只告警，
        // 否则用户会陷入"存不进、发不了、导不出"的死循环。
        assertFalse(checker.hasBlockingIssue(Arrays.asList(unresolved, extra)));
    }
}
