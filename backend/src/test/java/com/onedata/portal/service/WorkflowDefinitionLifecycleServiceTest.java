package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowExportJsonResponse;
import com.onedata.portal.dto.workflow.WorkflowImportCommitRequest;
import com.onedata.portal.dto.workflow.WorkflowImportCommitResponse;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewRequest;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionLifecycleServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
    }

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    private WorkflowDefinitionLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowDefinitionLifecycleService(
                runtimeDefinitionService,
                sqlTableMatcherService,
                dolphinSchedulerService,
                dataTaskService,
                workflowService,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowVersionMapper,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void previewShouldRequireRelationDecisionWhenDeclaredMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertNotNull(response.getRelationCompareDetail());
        assertFalse(response.getRelationCompareDetail().getOnlyInDeclared().isEmpty());
        assertFalse(response.getRelationCompareDetail().getOnlyInInferred().isEmpty());
    }

    @Test
    void previewShouldFailWhenTaskHasNoOutputTable() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_only_input", "SQL_A");
        definition.setTasks(Collections.singletonList(task1));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(11L, null));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("输出表")));
    }

    @Test
    void commitShouldFailWhenRelationDecisionMissingUnderMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.commit(request));
        assertTrue(ex.getMessage().contains("relationDecision"));
    }

    @Test
    void commitShouldCreateWorkflowWhenPreviewPassed() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        AtomicLong taskIdSequence = new AtomicLong(10L);
        when(dataTaskService.create(any(), any(), any())).thenAnswer(invocation -> {
            DataTask payload = invocation.getArgument(0);
            DataTask persisted = new DataTask();
            persisted.setId(taskIdSequence.incrementAndGet());
            persisted.setTaskName(payload.getTaskName());
            persisted.setTaskCode(payload.getTaskCode());
            return persisted;
        });

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(77L);
        createdWorkflow.setWorkflowName("wf_import_demo");
        createdWorkflow.setCurrentVersionId(900L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(900L);
        version.setVersionNo(3);
        when(workflowVersionMapper.selectById(900L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setOperator("tester");
        WorkflowImportCommitResponse response = service.commit(request);

        assertEquals(77L, response.getWorkflowId());
        assertEquals(900L, response.getVersionId());
        assertEquals(3, response.getVersionNo());
        assertEquals(2, response.getCreatedTaskCount());
        assertEquals("INFERRED", response.getAppliedRelationDecision());

        ArgumentCaptor<WorkflowDefinitionRequest> requestCaptor = ArgumentCaptor.forClass(WorkflowDefinitionRequest.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        WorkflowDefinitionRequest captured = requestCaptor.getValue();
        assertEquals("import_file", captured.getTriggerSource());
        assertNotNull(captured.getDefinitionJson());
        assertTrue(captured.getDefinitionJson().contains("processTaskRelationList"));
    }

    @Test
    void previewShouldLoadDefinitionFromDolphinSource() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_from_dolphin");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 2001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setSourceType("dolphin");
        request.setProjectCode(1L);
        request.setWorkflowCode(2001L);
        request.setWorkflowName("wf_from_dolphin_imported");
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertEquals("wf_from_dolphin_imported", response.getWorkflowName());
    }

    @Test
    void commitShouldUseDolphinImportTriggerAndCustomName() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_from_dolphin");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 3001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        AtomicLong taskIdSequence = new AtomicLong(20L);
        when(dataTaskService.create(any(), any(), any())).thenAnswer(invocation -> {
            DataTask payload = invocation.getArgument(0);
            DataTask persisted = new DataTask();
            persisted.setId(taskIdSequence.incrementAndGet());
            persisted.setTaskName(payload.getTaskName());
            persisted.setTaskCode(payload.getTaskCode());
            return persisted;
        });

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(99L);
        createdWorkflow.setCurrentVersionId(901L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(901L);
        version.setVersionNo(4);
        when(workflowVersionMapper.selectById(901L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setSourceType("dolphin");
        request.setProjectCode(1L);
        request.setWorkflowCode(3001L);
        request.setWorkflowName("wf_custom_import_name");
        request.setOperator("tester");
        WorkflowImportCommitResponse response = service.commit(request);

        assertEquals(99L, response.getWorkflowId());
        ArgumentCaptor<WorkflowDefinitionRequest> requestCaptor = ArgumentCaptor.forClass(WorkflowDefinitionRequest.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        WorkflowDefinitionRequest captured = requestCaptor.getValue();
        assertEquals("wf_custom_import_name", captured.getWorkflowName());
        assertEquals("import_dolphin", captured.getTriggerSource());

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        DataWorkflow updated = workflowCaptor.getValue();
        assertNull(updated.getWorkflowCode());
        assertEquals("import", updated.getSyncSource());
    }

    @Test
    void exportShouldReturnWorkflowDefinitionJson() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(12L);
        workflow.setWorkflowName("wf_export_demo");

        when(dataWorkflowMapper.selectById(12L)).thenReturn(workflow);
        when(workflowService.buildDefinitionJsonForExport(12L)).thenReturn("{\"ok\":true}");

        WorkflowExportJsonResponse response = service.exportJson(12L);

        assertEquals("wf_export_demo.json", response.getFileName());
        assertEquals("{\"ok\":true}", response.getContent());
    }

    @Test
    void previewShouldFailWhenDolphinImportWorkflowNameConflict() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_conflict");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        definition.setTasks(Collections.singletonList(task1));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 4001L)).thenReturn(definition);
        DataWorkflow existing = new DataWorkflow();
        existing.setId(123L);
        existing.setWorkflowName("wf_conflict");
        when(dataWorkflowMapper.selectOne(any())).thenReturn(existing);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setSourceType("dolphin");
        request.setProjectCode(1L);
        request.setWorkflowCode(4001L);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("工作流名称已存在")));
    }

    private RuntimeWorkflowDefinition baseDefinition() {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName("wf_import_demo");
        definition.setDescription("import");
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition sqlTask(Long taskCode, String taskName, String sql) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceName("mysql_ds");
        task.setDatasourceType("MYSQL");
        task.setTaskVersion(1);
        return task;
    }

    private SqlTableAnalyzeResponse analyze(Long inputTableId, Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        if (inputTableId != null) {
            response.setInputRefs(Collections.singletonList(matchedRef("db.t_in_" + inputTableId, inputTableId)));
        } else {
            response.setInputRefs(Collections.emptyList());
        }
        if (outputTableId != null) {
            response.setOutputRefs(Collections.singletonList(matchedRef("db.t_out_" + outputTableId, outputTableId)));
        } else {
            response.setOutputRefs(Collections.emptyList());
        }
        return response;
    }

    private SqlTableAnalyzeResponse.TableRefMatch matchedRef(String rawName, Long tableId) {
        SqlTableAnalyzeResponse.TableRefMatch match = new SqlTableAnalyzeResponse.TableRefMatch();
        match.setRawName(rawName);
        match.setMatchStatus("matched");
        SqlTableAnalyzeResponse.TableCandidate table = new SqlTableAnalyzeResponse.TableCandidate();
        table.setTableId(tableId);
        table.setTableName(rawName);
        match.setChosenTable(table);
        return match;
    }
}
