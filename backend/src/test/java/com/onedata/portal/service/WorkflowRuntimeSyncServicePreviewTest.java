package com.onedata.portal.service;

import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncErrorCodes;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncParitySummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeSyncServicePreviewTest {

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private WorkflowRuntimeDiffService runtimeDiffService;

    @Mock
    private RuntimeSyncParityService runtimeSyncParityService;

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowRuntimeSyncService service;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowRuntimeSyncRecord.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "runtimeSyncEnabled", true);
        ReflectionTestUtils.setField(service, "runtimeSyncIngestMode", "legacy");

        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot = new WorkflowRuntimeDiffService.RuntimeSnapshot();
        snapshot.setSnapshotHash("hash");
        snapshot.setSnapshotJson("{}");
        snapshot.setSnapshotNode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        lenient().when(runtimeDiffService.buildSnapshot(any(), any())).thenReturn(snapshot);
        lenient().when(runtimeDiffService.buildDiff(any(), any())).thenReturn(new RuntimeDiffSummary());
        RuntimeSyncParitySummary paritySummary = new RuntimeSyncParitySummary();
        paritySummary.setStatus(RuntimeSyncParityService.STATUS_NOT_CHECKED);
        lenient().when(runtimeSyncParityService.compare(any(), any())).thenReturn(paritySummary);
        lenient().when(runtimeSyncParityService.isInconsistent(anyString())).thenReturn(false);
        lenient().when(runtimeSyncParityService.toJson(any())).thenReturn(null);
        lenient().when(runtimeSyncParityService.parse(anyString())).thenReturn(null);

        lenient().when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(dataWorkflowMapper.selectOne(any())).thenReturn(null);
        lenient().when(workflowRuntimeSyncRecordMapper.selectOne(any())).thenReturn(null);
        lenient().when(dolphinSchedulerService.listDatasources(any(), any()))
                .thenReturn(Collections.singletonList(datasource(10L, "doris_ds")));
    }

    @Test
    void previewShouldFailWhenContainsNonSqlTask() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition shellTask = new RuntimeTaskDefinition();
        shellTask.setTaskCode(1L);
        shellTask.setTaskName("shell-node");
        shellTask.setNodeType("SHELL");
        definition.setTasks(Collections.singletonList(shellTask));

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertFalse(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.UNSUPPORTED_NODE_TYPE.equals(issue.getCode())));
    }

    @Test
    void previewShouldFailWhenSqlTableAmbiguous() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition sqlTask = sqlTask(1L, "task_a", "INSERT INTO dws.t1 SELECT * FROM ods.t0", 10L);
        definition.setTasks(Collections.singletonList(sqlTask));

        SqlTableAnalyzeResponse analyze = matchedAnalyze(11L, 22L);
        analyze.setAmbiguous(Collections.singletonList("dws.t1"));
        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(sqlTask.getSql()), eq("SQL"))).thenReturn(analyze);

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertFalse(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.SQL_TABLE_AMBIGUOUS.equals(issue.getCode())));
    }

    @Test
    void previewShouldAllowSqlWithoutInputButWithOutput() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition sqlTask = sqlTask(1L, "task_a", "UPDATE dws.t1 SET c1 = 1", 10L);
        definition.setTasks(Collections.singletonList(sqlTask));

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(sqlTask.getSql()), eq("SQL")))
                .thenReturn(outputOnlyAnalyze(22L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .noneMatch(issue -> RuntimeSyncErrorCodes.SQL_LINEAGE_INCOMPLETE.equals(issue.getCode())));
    }

    @Test
    void previewShouldPassWhenUpdateHeadFeedsDownstreamAndLineageEdgesMatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskUpdate = sqlTask(1L, "task_update", "UPDATE ods.user_tag SET tag = 1", 10L);
        RuntimeTaskDefinition taskInsert = sqlTask(2L, "task_insert",
                "INSERT INTO dws.user_tag_di SELECT * FROM ods.user_tag", 10L);
        definition.setTasks(Arrays.asList(taskUpdate, taskInsert));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(taskUpdate.getSql()), eq("SQL")))
                .thenReturn(outputOnlyAnalyze(501L));
        when(sqlTableMatcherService.analyze(eq(taskInsert.getSql()), eq("SQL")))
                .thenReturn(matchedAnalyze(501L, 601L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync(), "UPDATE 首节点仅输出场景预检应通过");
        assertTrue(response.getErrors().isEmpty(), "预检不应返回错误");
        assertTrue(response.getWarnings().stream().noneMatch(issue -> "EDGE_MISMATCH".equals(issue.getCode())),
                "平台存储边与 SQL 推断边应一致，不应出现 EDGE_MISMATCH");
        assertTrue(response.getEdgeMismatchDetail() == null, "边一致时不应返回 mismatch detail");
    }

    @Test
    void previewShouldFailWhenRuntimeExplicitEdgesMissingInStrictMode() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskUpdate = sqlTask(1L, "task_update", "UPDATE ods.user_tag SET tag = 1", 10L);
        RuntimeTaskDefinition taskInsert = sqlTask(2L, "task_insert",
                "INSERT INTO dws.user_tag_di SELECT * FROM ods.user_tag", 10L);
        definition.setTasks(Arrays.asList(taskUpdate, taskInsert));
        definition.setExplicitEdges(Collections.emptyList());

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(taskUpdate.getSql()), eq("SQL")))
                .thenReturn(outputOnlyAnalyze(501L));
        when(sqlTableMatcherService.analyze(eq(taskInsert.getSql()), eq("SQL")))
                .thenReturn(matchedAnalyze(501L, 601L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertFalse(response.getCanSync(), "严格模式下显式边缺失应阻断预检");
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.DOLPHIN_EXPLICIT_EDGE_MISSING.equals(issue.getCode())));
    }

    @Test
    void previewShouldWarnWhenExplicitAndInferredEdgesMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        // explicit edges intentionally set to opposite direction, inferred edges should include 1->2
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync());
        assertEquals(0, response.getErrors().size());
        assertTrue(response.getWarnings().stream()
                .anyMatch(issue -> "EDGE_MISMATCH".equals(issue.getCode())));
    }

    @Test
    void syncShouldRequireManualConfirmationWhenEdgeMismatchExists() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        RuntimeSyncExecuteRequest request = new RuntimeSyncExecuteRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        request.setOperator("tester");
        request.setConfirmEdgeMismatch(false);

        RuntimeSyncExecuteResponse response = service.sync(request);

        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.EDGE_MISMATCH_CONFIRM_REQUIRED.equals(issue.getCode())));
        assertTrue(response.getEdgeMismatchDetail() != null);
    }

    @Test
    void previewShouldAllowButWarnWhenParityMismatchExists() {
        ReflectionTestUtils.setField(service, "runtimeSyncIngestMode", "export_shadow");

        RuntimeWorkflowDefinition exportDefinition = baseDefinition();
        RuntimeTaskDefinition task = sqlTask(1L, "task_a", "SELECT * FROM dwd.t1", 10L);
        exportDefinition.setTasks(Collections.singletonList(task));
        exportDefinition.setExplicitEdges(Collections.emptyList());

        RuntimeWorkflowDefinition legacyDefinition = baseDefinition();
        RuntimeTaskDefinition legacyTask = sqlTask(1L, "task_a_legacy", "SELECT * FROM dwd.t1", 10L);
        legacyDefinition.setTasks(Collections.singletonList(legacyTask));
        legacyDefinition.setExplicitEdges(Collections.emptyList());

        RuntimeSyncParitySummary paritySummary = new RuntimeSyncParitySummary();
        paritySummary.setStatus(RuntimeSyncParityService.STATUS_INCONSISTENT);
        paritySummary.setChanged(true);
        paritySummary.setWorkflowFieldDiffCount(1);

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(exportDefinition);
        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(legacyDefinition);
        when(runtimeSyncParityService.compare(any(), any())).thenReturn(paritySummary);
        when(runtimeSyncParityService.isInconsistent(eq(RuntimeSyncParityService.STATUS_INCONSISTENT))).thenReturn(true);
        when(sqlTableMatcherService.analyze(eq(task.getSql()), eq("SQL"))).thenReturn(matchedAnalyze(11L, 22L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync());
        assertEquals(RuntimeSyncParityService.STATUS_INCONSISTENT, response.getParityStatus());
        assertTrue(response.getWarnings().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.DEFINITION_PARITY_MISMATCH.equals(issue.getCode())));
    }

    @Test
    void syncShouldBlockWhenParityMismatchExists() {
        ReflectionTestUtils.setField(service, "runtimeSyncIngestMode", "export_shadow");

        RuntimeWorkflowDefinition exportDefinition = baseDefinition();
        RuntimeTaskDefinition task = sqlTask(1L, "task_a", "SELECT * FROM dwd.t1", 10L);
        exportDefinition.setTasks(Collections.singletonList(task));
        exportDefinition.setExplicitEdges(Collections.emptyList());

        RuntimeWorkflowDefinition legacyDefinition = baseDefinition();
        RuntimeTaskDefinition legacyTask = sqlTask(1L, "task_a_legacy", "SELECT * FROM dwd.t1", 10L);
        legacyDefinition.setTasks(Collections.singletonList(legacyTask));
        legacyDefinition.setExplicitEdges(Collections.emptyList());

        RuntimeSyncParitySummary paritySummary = new RuntimeSyncParitySummary();
        paritySummary.setStatus(RuntimeSyncParityService.STATUS_INCONSISTENT);
        paritySummary.setChanged(true);
        paritySummary.setWorkflowFieldDiffCount(1);

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(exportDefinition);
        when(runtimeDefinitionService.loadRuntimeDefinition(1L, 1001L)).thenReturn(legacyDefinition);
        when(runtimeSyncParityService.compare(any(), any())).thenReturn(paritySummary);
        when(runtimeSyncParityService.isInconsistent(eq(RuntimeSyncParityService.STATUS_INCONSISTENT))).thenReturn(true);
        when(sqlTableMatcherService.analyze(eq(task.getSql()), eq("SQL"))).thenReturn(matchedAnalyze(11L, 22L));

        RuntimeSyncExecuteRequest request = new RuntimeSyncExecuteRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        request.setOperator("tester");
        request.setConfirmEdgeMismatch(true);

        RuntimeSyncExecuteResponse response = service.sync(request);

        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.DEFINITION_PARITY_MISMATCH.equals(issue.getCode())));
    }

    private RuntimeWorkflowDefinition baseDefinition() {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName("wf_runtime");
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition sqlTask(Long taskCode, String taskName, String sql, Long datasourceId) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceId(datasourceId);
        task.setDatasourceType("DORIS");
        return task;
    }

    private SqlTableAnalyzeResponse matchedAnalyze(Long inputTableId, Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        response.getInputRefs().add(matchedRef("input_tbl", inputTableId));
        response.getOutputRefs().add(matchedRef("output_tbl", outputTableId));
        return response;
    }

    private SqlTableAnalyzeResponse outputOnlyAnalyze(Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        response.getOutputRefs().add(matchedRef("output_tbl", outputTableId));
        return response;
    }

    private SqlTableAnalyzeResponse.TableRefMatch matchedRef(String rawName, Long tableId) {
        SqlTableAnalyzeResponse.TableRefMatch ref = new SqlTableAnalyzeResponse.TableRefMatch();
        ref.setRawName(rawName);
        ref.setMatchStatus("matched");
        SqlTableAnalyzeResponse.TableCandidate chosen = new SqlTableAnalyzeResponse.TableCandidate();
        chosen.setTableId(tableId);
        ref.setChosenTable(chosen);
        return ref;
    }

    private DolphinDatasourceOption datasource(Long id, String name) {
        DolphinDatasourceOption option = new DolphinDatasourceOption();
        option.setId(id);
        option.setName(name);
        option.setType("DORIS");
        return option;
    }
}
