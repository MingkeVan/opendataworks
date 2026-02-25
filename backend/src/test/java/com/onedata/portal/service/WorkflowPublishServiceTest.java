package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowPublishServiceTest {

    @Mock
    private WorkflowPublishRecordMapper publishRecordMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private WorkflowRuntimeDiffService runtimeDiffService;

    @Mock
    private WorkflowDeployService workflowDeployService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowPublishService service;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
    }

    @BeforeEach
    void setUp() {
        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot = new WorkflowRuntimeDiffService.RuntimeSnapshot();
        snapshot.setSnapshotJson("{}");
        snapshot.setSnapshotHash("hash");
        snapshot.setSnapshotNode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        lenient().when(runtimeDiffService.buildSnapshot(any(), any())).thenReturn(snapshot);
    }

    @Test
    void publishDeployShouldRequireConfirmWhenDiffExists() {
        DataWorkflow workflow = workflow(1L, null, 101L);
        WorkflowVersion version = version(101L, 1);
        mockPreviewInputs(workflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(101L)).thenReturn(version);
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(changedDiff());

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.publish(1L, request));
        assertTrue(ex.getMessage().contains("PUBLISH_DIFF_CONFIRM_REQUIRED"));
        verify(workflowDeployService, never()).deploy(any());
    }

    @Test
    void publishDeployShouldSucceedWhenDiffConfirmed() {
        DataWorkflow workflow = workflow(1L, null, 101L);
        WorkflowVersion version = version(101L, 2);
        mockPreviewInputs(workflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(101L)).thenReturn(version);
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(changedDiff());

        WorkflowDeployService.DeploymentResult result =
                new WorkflowDeployService.DeploymentResult(90001L, 11L, 1, false);
        when(workflowDeployService.deploy(eq(workflow))).thenReturn(result);

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(true);

        WorkflowPublishRecord record = service.publish(1L, request);
        assertEquals("success", record.getStatus());
        assertEquals(90001L, record.getEngineWorkflowCode());
    }

    @Test
    void previewPublishShouldExposeReadableDiffAcrossWorkflowScheduleTasksAndEdges() {
        WorkflowPublishService previewService = buildPreviewServiceWithRealDiff();

        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setDescription("platform description");
        workflow.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        workflow.setDolphinScheduleId(900L);
        workflow.setScheduleState("ONLINE");
        workflow.setScheduleCron("0 0 1 * * ? *");
        workflow.setScheduleTimezone("Asia/Shanghai");
        workflow.setScheduleStartTime(LocalDateTime.of(2026, 2, 24, 1, 0, 0));
        workflow.setScheduleEndTime(LocalDateTime.of(2026, 8, 24, 1, 0, 0));
        workflow.setScheduleWorkerGroup("wg_platform");
        workflow.setScheduleTenantCode("tenant_platform");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relA = new WorkflowTaskRelation();
        relA.setWorkflowId(workflow.getId());
        relA.setTaskId(10L);
        WorkflowTaskRelation relB = new WorkflowTaskRelation();
        relB.setWorkflowId(workflow.getId());
        relB.setTaskId(20L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relA, relB));

        DataTask taskA = new DataTask();
        taskA.setId(10L);
        taskA.setTaskName("platform_task_a");
        taskA.setTaskSql("INSERT INTO dwd.order_user SELECT * FROM ods.order_src");
        taskA.setTaskDesc("task-a");
        taskA.setDolphinNodeType("SQL");
        taskA.setDolphinTaskCode(1001L);
        taskA.setDatasourceName("doris_ds");
        taskA.setDatasourceType("DORIS");

        DataTask taskB = new DataTask();
        taskB.setId(20L);
        taskB.setTaskName("platform_task_b");
        taskB.setTaskSql("INSERT INTO dws.order_user_di SELECT * FROM dwd.order_user");
        taskB.setTaskDesc("task-b");
        taskB.setDolphinNodeType("SQL");
        taskB.setDolphinTaskCode(2002L);
        taskB.setDatasourceName("doris_ds");
        taskB.setDatasourceType("DORIS");
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(taskA, taskB));

        TableTaskRelation relReadA = tableTaskRelation(10L, 401L, "read");
        TableTaskRelation relReadB = tableTaskRelation(20L, 501L, "read");
        TableTaskRelation relWriteA = tableTaskRelation(10L, 501L, "write");
        TableTaskRelation relWriteB = tableTaskRelation(20L, 601L, "write");
        when(tableTaskRelationMapper.selectList(any()))
                .thenReturn(Arrays.asList(relReadA, relReadB), Arrays.asList(relWriteA, relWriteB));

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_runtime");
        runtimeDefinition.setDescription("runtime description");
        runtimeDefinition.setGlobalParams("[{\"prop\":\"k\",\"value\":\"runtime\"}]");
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 15 2 * * ? *",
                "UTC",
                "wg_runtime",
                "tenant_runtime"));
        runtimeDefinition.setTasks(Arrays.asList(
                runtimeTask(1001L,
                        "platform_task_a",
                        "INSERT INTO dwd.order_user SELECT user_id FROM ods.order_src",
                        Arrays.asList(401L),
                        Arrays.asList(501L)),
                runtimeTask(3003L,
                        "runtime_task_c",
                        "INSERT INTO ads.order_user SELECT * FROM dwd.order_user",
                        Arrays.asList(501L),
                        Arrays.asList(701L))));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        WorkflowPublishPreviewResponse preview = previewService.previewPublish(1L);

        assertTrue(preview.getErrors().isEmpty(), "预检不应返回错误");
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()), "预检应允许发布");
        assertTrue(Boolean.TRUE.equals(preview.getRequireConfirm()), "有差异时应要求确认");
        assertNotNull(preview.getDiffSummary(), "差异摘要不能为空");
        assertTrue(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "应识别到变更");

        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.workflowName")
                        && item.contains("wf_runtime")
                        && item.contains("wf_platform")));
        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.description")));
        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.globalParams")));

        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.crontab")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.timezoneId")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.workerGroup")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.tenantCode")));

        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .anyMatch(item -> item.contains("platform_task_a") && item.contains("sql")));
        assertTrue(preview.getDiffSummary().getTaskAdded().stream()
                .anyMatch(item -> item.contains("platform_task_b") && item.contains("taskCode=2002")));
        assertTrue(preview.getDiffSummary().getTaskRemoved().stream()
                .anyMatch(item -> item.contains("runtime_task_c") && item.contains("taskCode=3003")));

        assertTrue(preview.getDiffSummary().getEdgeAdded().stream()
                .anyMatch(item -> item.contains("platform_task_a")
                        && item.contains("platform_task_b")
                        && item.contains("1001->2002")));
        assertTrue(preview.getDiffSummary().getEdgeRemoved().stream()
                .anyMatch(item -> item.contains("platform_task_a")
                        && item.contains("runtime_task_c")
                        && item.contains("1001->3003")));
        assertFalse(preview.getDiffSummary().getEdgeAdded().isEmpty(), "应识别边新增");
        assertFalse(preview.getDiffSummary().getEdgeRemoved().isEmpty(), "应识别边删除");
    }

    private WorkflowPublishService buildPreviewServiceWithRealDiff() {
        return new WorkflowPublishService(
                publishRecordMapper,
                workflowVersionMapper,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowTaskRelationMapper,
                tableTaskRelationMapper,
                runtimeDefinitionService,
                new WorkflowRuntimeDiffService(new com.fasterxml.jackson.databind.ObjectMapper()),
                workflowDeployService,
                dolphinSchedulerService,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void mockPreviewInputs(DataWorkflow workflow) {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(workflow.getId());
        relation.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        DataTask task = new DataTask();
        task.setId(10L);
        task.setTaskName("task_a");
        task.setTaskSql("INSERT INTO dws.t1 SELECT * FROM ods.t1");
        task.setDolphinNodeType("SQL");
        task.setTaskDesc("desc");
        task.setDolphinTaskCode(10001L);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(task));

        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private DataWorkflow workflow(Long id, Long workflowCode, Long currentVersionId) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(id);
        workflow.setWorkflowName("wf_test");
        workflow.setProjectCode(11L);
        workflow.setWorkflowCode(workflowCode);
        workflow.setCurrentVersionId(currentVersionId);
        workflow.setStatus("draft");
        return workflow;
    }

    private WorkflowVersion version(Long id, Integer versionNo) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setVersionNo(versionNo);
        return version;
    }

    private com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary changedDiff() {
        com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary diff =
                new com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary();
        diff.setChanged(true);
        diff.setTaskAdded(Collections.singletonList("task_a [taskCode=10001]"));
        return diff;
    }

    private TableTaskRelation tableTaskRelation(Long taskId, Long tableId, String relationType) {
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(tableId);
        relation.setRelationType(relationType);
        return relation;
    }

    private RuntimeWorkflowSchedule runtimeSchedule(String cron, String timezone, String workerGroup, String tenantCode) {
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(901L);
        schedule.setReleaseState("ONLINE");
        schedule.setCrontab(cron);
        schedule.setTimezoneId(timezone);
        schedule.setStartTime("2026-02-24 02:15:00");
        schedule.setEndTime("2026-08-24 02:15:00");
        schedule.setWorkerGroup(workerGroup);
        schedule.setTenantCode(tenantCode);
        return schedule;
    }

    private RuntimeTaskDefinition runtimeTask(Long code,
            String name,
            String sql,
            List<Long> inputTableIds,
            List<Long> outputTableIds) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setNodeType("SQL");
        task.setDatasourceId(10L);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("DORIS");
        task.setSql(sql);
        task.setInputTableIds(inputTableIds);
        task.setOutputTableIds(outputTableIds);
        return task;
    }
}
