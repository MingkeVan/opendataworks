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

    /**
     * 关系表推导出 7 -> 8 这条边（7 写 tbl 5，8 读 tbl 5）。
     * 两个节点的 inputTableIds/outputTableIds 都正确，只有 processTaskRelationList 少了这条边。
     */
    private DataWorkflow workflowWithTwoTasks(String processTaskRelationList) {
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]},"
                + "{\"taskCode\":1008,\"xPlatformTaskMeta\":{\"taskId\":8},"
                + "\"inputTableIds\":[5],\"outputTableIds\":[6]}],"
                + "\"processTaskRelationList\":" + processTaskRelationList + "}");
        return workflow;
    }

    private void stubTwoTaskWorkflow(DataWorkflow workflow) {
        DataTask upstream = sqlTask(7L, "upstream");
        DataTask downstream = sqlTask(8L, "downstream");
        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        for (DataTask task : Arrays.asList(upstream, downstream)) {
            WorkflowTaskRelation binding = new WorkflowTaskRelation();
            binding.setWorkflowId(workflow.getId());
            binding.setTaskId(task.getId());
            bindings.add(binding);
        }
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(upstream, downstream));
        // read: 8 读 5；write: 7 写 5，8 写 6
        when(tableTaskRelationMapper.selectList(any())).thenReturn(
                Collections.singletonList(relation(8L, 5L, "read")),
                Arrays.asList(relation(7L, 5L, "write"), relation(8L, 6L, "write")));
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    @Test
    void definitionDriftDetectsMissingProcessTaskRelationEdge() {
        // 表数组全对，只有流程边少了一条：这正是发布出去的 DAG 会缺依赖的情况。
        // 只比对 taskDefinitionList 的 inputTableIds/outputTableIds 是发现不了的。
        DataWorkflow workflow = workflowWithTwoTasks("[{\"preTaskCode\":0,\"postTaskCode\":1007}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                                && "workflow.definitionJson.processTaskRelationList".equals(issue.getField())
                                && issue.getMessage().contains("缺少")),
                "应报告缺失的任务依赖边，实际问题: " + issues);
    }

    @Test
    void definitionDriftAcceptsMatchingProcessTaskRelationEdges() {
        DataWorkflow workflow = workflowWithTwoTasks(
                "[{\"preTaskCode\":0,\"postTaskCode\":1007},{\"preTaskCode\":1007,\"postTaskCode\":1008}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().noneMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())),
                "边与表清单都一致时不应报漂移，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsExtraProcessTaskRelationEdge() {
        DataWorkflow workflow = workflowWithTwoTasks(
                "[{\"preTaskCode\":0,\"postTaskCode\":1007},{\"preTaskCode\":1007,\"postTaskCode\":1008},"
                        + "{\"preTaskCode\":1008,\"postTaskCode\":1007}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                                && issue.getMessage().contains("不存在的任务依赖边")),
                "应报告多余的任务依赖边，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsTaskNodeMissingFromDefinition() {
        DataWorkflow workflow = workflow();
        // 定义里只有 7，工作流实际绑定了 7 和 8
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]}],"
                + "\"processTaskRelationList\":[]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        "workflow.definitionJson.taskDefinitionList".equals(issue.getField())
                                && issue.getMessage().contains("缺少该任务节点")),
                "应报告定义中缺失的任务节点，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsTaskNodeNotBoundToWorkflow() {
        DataWorkflow workflow = workflowWithTwoTasks("[]");
        DataTask only = sqlTask(7L, "upstream");
        WorkflowTaskRelation binding = new WorkflowTaskRelation();
        binding.setWorkflowId(workflow.getId());
        binding.setTaskId(7L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(only));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("未绑定到该工作流的任务节点")),
                "应报告定义中多余的任务节点，实际问题: " + issues);
    }

    @Test
    void malformedDefinitionJsonDegradesGracefullyInsteadOfFailingThePreview() {
        // definitionJson 可能来自导入或历史版本，格式不受本模块控制。
        // 解析失败只应放弃漂移比对，不能让发布预检和导出整体报错。
        for (String broken : Arrays.asList(
                "{not json",
                "[]",
                "{\"taskDefinitionList\":\"not-an-array\"}",
                "{\"taskDefinitionList\":[{}],\"processTaskRelationList\":\"nope\"}")) {
            DataWorkflow workflow = workflow();
            workflow.setDefinitionJson(broken);
            stubTwoTaskWorkflow(workflow);

            List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

            assertTrue(issues.stream().noneMatch(issue ->
                            TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())),
                    "definitionJson 无法解析时不应产生漂移问题，输入: " + broken);
        }
    }

    @Test
    void sqlTaskWithoutSqlTextIsSkippedInsteadOfBeingFlagged() {
        // 节点类型是 SQL 但 taskSql 为空（草稿态常见），没有可解析的内容，不应报任何 SQL 类问题。
        DataTask task = sqlTask(7L, "draft");
        task.setTaskSql(null);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                task, Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());

        task.setTaskSql("   ");
        assertTrue(checker.findHighConfidenceGap(task, Collections.emptyList(), Collections.emptyList())
                .isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());
    }

    @Test
    void sqlParseFailureDoesNotBlockSave() {
        // 解析器对方言 SQL 存在误判空间，抛异常时必须放行而不是拒绝保存。
        when(sqlTableMatcherService.analyze(anyString(), anyString()))
                .thenThrow(new IllegalStateException("parser blew up"));

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
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
