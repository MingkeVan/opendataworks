package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDeleteResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionErrorCodes;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionOperationServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowPublishRecord.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowRuntimeSyncRecord.class);
    }

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowPublishRecordMapper workflowPublishRecordMapper;

    @Mock
    private WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowVersionOperationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowVersionOperationService(
                workflowVersionMapper,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowPublishRecordMapper,
                workflowRuntimeSyncRecordMapper,
                dataTaskService,
                workflowService,
                objectMapper);
    }

    @Test
    void compareShouldSwapLeftAndRightWhenLeftGreaterThanRight() {
        WorkflowVersion version1 = version(1L, 11L, 1, canonicalSnapshot("wf", "task_a"));
        WorkflowVersion version2 = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));

        when(workflowVersionMapper.selectById(1L)).thenReturn(version1);
        when(workflowVersionMapper.selectById(2L)).thenReturn(version2);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(1L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertEquals(1L, response.getLeftVersionId());
        assertEquals(2L, response.getRightVersionId());
        assertNotNull(response.getRawDiff());
        assertTrue(response.getRawDiff().contains("--- v1"));
        assertTrue(response.getRawDiff().contains("+++ v2"));
    }

    @Test
    void compareShouldFailWhenLeftEqualsRight() {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(2L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.compare(11L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID));
    }

    @Test
    void compareShouldTreatNullLeftAsEmptyBaseline() {
        WorkflowVersion rightVersion = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));
        when(workflowVersionMapper.selectById(2L)).thenReturn(rightVersion);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(null);
        request.setRightVersionId(2L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertTrue(Boolean.TRUE.equals(response.getChanged()));
        assertTrue((response.getAdded().getTasks().size() + response.getAdded().getWorkflowFields().size()) > 0);
        assertEquals(0, response.getSummary().getRemoved());
        assertNotNull(response.getRawDiff());
        assertTrue(response.getRawDiff().contains("--- empty"));
    }

    @Test
    void rollbackShouldFailForLegacySnapshot() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setWorkflowName("wf");

        WorkflowVersion legacyVersion = version(1L, 11L, 1,
                "{\"workflowId\":11,\"workflowName\":\"wf\",\"tasks\":[{\"taskId\":1}]}");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(1L)).thenReturn(legacyVersion);

        WorkflowVersionRollbackRequest request = new WorkflowVersionRollbackRequest();
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(11L, 1L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED));
    }

    @Test
    void deleteShouldFailWhenTargetIsLatestSuccessfulPublishedVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(5L);
        WorkflowVersion target = version(4L, 11L, 4, canonicalSnapshot("wf", "task_a"));
        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(99L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(4L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(4L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteVersion(11L, 4L));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN));
        verify(workflowVersionMapper, never()).delete(any());
    }

    @Test
    void deleteShouldFailWhenTargetIsCurrentVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(5L);
        WorkflowVersion target = version(5L, 11L, 5, canonicalSnapshot("wf", "task_a"));
        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(102L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(4L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(5L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteVersion(11L, 5L));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN));
        verify(workflowVersionMapper, never()).delete(any());
    }

    @Test
    void deleteShouldSucceedForDeletablePublishedHistoryVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(6L);
        WorkflowVersion target = version(4L, 11L, 4, canonicalSnapshot("wf", "task_a"));

        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(101L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(6L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(4L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);
        when(workflowVersionMapper.delete(any())).thenReturn(1);

        WorkflowVersionDeleteResponse response = service.deleteVersion(11L, 4L);

        assertEquals(11L, response.getWorkflowId());
        assertEquals(4L, response.getDeletedVersionId());
        assertEquals(4, response.getDeletedVersionNo());
        verify(workflowVersionMapper).delete(any());
        verify(dataWorkflowMapper, never()).update(any(), any());
    }

    @Test
    void compareShouldDetectCoreDiffsAcrossMultipleWorkflowSaves() {
        final long workflowId = 11L;

        WorkflowVersion v1 = version(101L, workflowId, 1, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v2 = version(102L, workflowId, 2, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L)),
                        taskNode(3L, "agg_user", "insert into ads.user_cnt select count(*) from dwd.user", "ads_ds",
                                Arrays.asList(30L), Arrays.asList(40L))
                ),
                Arrays.asList(edgeNode(1L, 2L), edgeNode(2L, 3L))
        ));

        WorkflowVersion v3 = version(103L, workflowId, 3, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v4 = version(104L, workflowId, 4, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user where dt='${bizdate}'", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v5 = version(105L, workflowId, 5, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v6 = version(106L, workflowId, 6, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v7 = version(107L, workflowId, 7, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(11L), Arrays.asList(21L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v8 = version(108L, workflowId, 8, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(11L), Arrays.asList(21L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(2L, 1L))
        ));

        Map<Long, WorkflowVersion> versions = new LinkedHashMap<>();
        versions.put(v1.getId(), v1);
        versions.put(v2.getId(), v2);
        versions.put(v3.getId(), v3);
        versions.put(v4.getId(), v4);
        versions.put(v5.getId(), v5);
        versions.put(v6.getId(), v6);
        versions.put(v7.getId(), v7);
        versions.put(v8.getId(), v8);
        when(workflowVersionMapper.selectById(any())).thenAnswer(invocation -> {
            Long versionId = invocation.getArgument(0);
            return versions.get(versionId);
        });

        WorkflowVersionCompareResponse addTask = compare(workflowId, v1.getId(), v2.getId());
        assertListContains(addTask.getAdded().getTasks(), "agg_user", "新增任务应被识别");

        WorkflowVersionCompareResponse removeTask = compare(workflowId, v2.getId(), v3.getId());
        assertListContains(removeTask.getRemoved().getTasks(), "agg_user", "删除任务应被识别");

        WorkflowVersionCompareResponse modifySql = compare(workflowId, v3.getId(), v4.getId());
        assertListContains(modifySql.getModified().getTasks(), "extract_user", "任务 SQL 修改应被识别");

        WorkflowVersionCompareResponse modifyDatasource = compare(workflowId, v4.getId(), v5.getId());
        assertListContains(modifyDatasource.getModified().getTasks(), "extract_user", "任务数据源修改应被识别");

        WorkflowVersionCompareResponse modifyTaskName = compare(workflowId, v5.getId(), v6.getId());
        assertListContains(modifyTaskName.getModified().getTasks(), "extract_user_v2", "任务名称修改应被识别");

        WorkflowVersionCompareResponse modifyInputOutput = compare(workflowId, v6.getId(), v7.getId());
        assertListContains(modifyInputOutput.getModified().getTasks(), "extract_user_v2", "输入输出表修改应被识别");

        WorkflowVersionCompareResponse modifyRelation = compare(workflowId, v7.getId(), v8.getId());
        assertListContains(modifyRelation.getAdded().getEdges(), "2->1", "任务关系新增边应被识别");
        assertListContains(modifyRelation.getRemoved().getEdges(), "1->2", "任务关系删除边应被识别");
    }

    @Test
    void compareShouldDetectWorkflowAndScheduleDiffsAcrossMultipleWorkflowSaves() {
        final long workflowId = 22L;
        List<Map<String, Object>> tasks = Collections.singletonList(
                taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                        Collections.singletonList(10L), Collections.singletonList(20L))
        );
        List<Map<String, Object>> edges = Collections.emptyList();

        WorkflowVersion v1 = version(201L, workflowId, 1, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v1",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-01\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v2 = version(202L, workflowId, 2, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v1",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v3 = version(203L, workflowId, 3, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v4 = version(204L, workflowId, 4, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v5 = version(205L, workflowId, 5, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v6 = version(206L, workflowId, 6, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v7 = version(207L, workflowId, 7, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        WorkflowVersion v8 = version(208L, workflowId, 8, canonicalSnapshot(
                workflowNode("wf_schedule", null,
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", null,
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        WorkflowVersion v9 = version(209L, workflowId, 9, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v3",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-12-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        Map<Long, WorkflowVersion> versions = new LinkedHashMap<>();
        versions.put(v1.getId(), v1);
        versions.put(v2.getId(), v2);
        versions.put(v3.getId(), v3);
        versions.put(v4.getId(), v4);
        versions.put(v5.getId(), v5);
        versions.put(v6.getId(), v6);
        versions.put(v7.getId(), v7);
        versions.put(v8.getId(), v8);
        versions.put(v9.getId(), v9);
        when(workflowVersionMapper.selectById(any())).thenAnswer(invocation -> {
            Long versionId = invocation.getArgument(0);
            return versions.get(versionId);
        });

        WorkflowVersionCompareResponse modifyGlobalParams = compare(workflowId, v1.getId(), v2.getId());
        assertListContains(modifyGlobalParams.getModified().getWorkflowFields(),
                "workflow.globalParams", "全局变量修改应被识别");
        assertListContains(modifyGlobalParams.getUnchanged().getTasks(),
                "extract_user", "仅改全局变量时任务应保持不变");

        WorkflowVersionCompareResponse modifyDescription = compare(workflowId, v2.getId(), v3.getId());
        assertListContains(modifyDescription.getModified().getWorkflowFields(),
                "workflow.description", "工作流描述修改应被识别");

        WorkflowVersionCompareResponse modifyTaskGroup = compare(workflowId, v3.getId(), v4.getId());
        assertListContains(modifyTaskGroup.getModified().getWorkflowFields(),
                "workflow.taskGroupName", "工作流任务组修改应被识别");

        WorkflowVersionCompareResponse modifyScheduleCore = compare(workflowId, v4.getId(), v5.getId());
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleCron", "调度 cron 修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleTimezone", "调度时区修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleStartTime", "调度开始时间修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间修改应被识别");

        WorkflowVersionCompareResponse modifySchedulePolicy = compare(workflowId, v5.getId(), v6.getId());
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleFailureStrategy", "调度失败策略修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleWarningType", "调度告警类型修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleWarningGroupId", "调度告警组修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleProcessInstancePriority", "调度优先级修改应被识别");

        WorkflowVersionCompareResponse modifyScheduleRuntime = compare(workflowId, v6.getId(), v7.getId());
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleWorkerGroup", "调度 workerGroup 修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleTenantCode", "调度租户修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleEnvironmentCode", "调度环境修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleAutoOnline", "调度自动上线开关修改应被识别");

        WorkflowVersionCompareResponse removeWorkflowAndScheduleField = compare(workflowId, v7.getId(), v8.getId());
        assertListContains(removeWorkflowAndScheduleField.getRemoved().getWorkflowFields(),
                "workflow.description", "工作流描述删除应被识别");
        assertListContains(removeWorkflowAndScheduleField.getRemoved().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间删除应被识别");

        WorkflowVersionCompareResponse addWorkflowAndScheduleField = compare(workflowId, v8.getId(), v9.getId());
        assertListContains(addWorkflowAndScheduleField.getAdded().getWorkflowFields(),
                "workflow.description", "工作流描述新增应被识别");
        assertListContains(addWorkflowAndScheduleField.getAdded().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间新增应被识别");
    }

    private WorkflowVersion version(Long id, Long workflowId, Integer versionNo, String snapshot) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setWorkflowId(workflowId);
        version.setVersionNo(versionNo);
        version.setStructureSnapshot(snapshot);
        return version;
    }

    private WorkflowVersionCompareResponse compare(Long workflowId, Long leftVersionId, Long rightVersionId) {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(leftVersionId);
        request.setRightVersionId(rightVersionId);
        return service.compare(workflowId, request);
    }

    private void assertListContains(List<String> values, String expectedPart, String message) {
        assertTrue(values.stream().anyMatch(item -> item.contains(expectedPart)),
                message + "，实际内容: " + values);
    }

    private Map<String, Object> taskNode(Long taskId,
                                         String taskName,
                                         String taskSql,
                                         String datasourceName,
                                         List<Long> inputTableIds,
                                         List<Long> outputTableIds) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("taskId", taskId);
        node.put("taskName", taskName);
        node.put("taskSql", taskSql);
        node.put("datasourceName", datasourceName);
        node.put("inputTableIds", new ArrayList<>(inputTableIds));
        node.put("outputTableIds", new ArrayList<>(outputTableIds));
        return node;
    }

    private Map<String, Object> edgeNode(Long upstreamTaskId, Long downstreamTaskId) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("upstreamTaskId", upstreamTaskId);
        edge.put("downstreamTaskId", downstreamTaskId);
        return edge;
    }

    private String canonicalSnapshot(String workflowName, String taskName) {
        return canonicalSnapshot(
                workflowName,
                Collections.singletonList(taskNode(
                        1L,
                        taskName,
                        "select 1",
                        "default_ds",
                        Collections.singletonList(1L),
                        Collections.singletonList(2L)
                )),
                Collections.emptyList()
        );
    }

    private String canonicalSnapshot(String workflowName,
                                     List<Map<String, Object>> tasks,
                                     List<Map<String, Object>> edges) {
        return canonicalSnapshot(workflowNode(workflowName, null, null, null), tasks, edges, new LinkedHashMap<>());
    }

    private String canonicalSnapshot(Map<String, Object> workflow,
                                     List<Map<String, Object>> tasks,
                                     List<Map<String, Object>> edges,
                                     Map<String, Object> schedule) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 2);
        root.put("workflow", workflow);
        root.put("tasks", tasks);
        root.put("edges", edges);
        root.put("schedule", schedule == null ? new LinkedHashMap<>() : schedule);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("构建测试快照失败", ex);
        }
    }

    private Map<String, Object> workflowNode(String workflowName,
                                             String description,
                                             String globalParams,
                                             String taskGroupName) {
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("workflowName", workflowName);
        workflow.put("description", description);
        workflow.put("globalParams", globalParams);
        workflow.put("taskGroupName", taskGroupName);
        return workflow;
    }

    private Map<String, Object> scheduleNode(String scheduleCron,
                                             String scheduleTimezone,
                                             String scheduleStartTime,
                                             String scheduleEndTime,
                                             String scheduleFailureStrategy,
                                             String scheduleWarningType,
                                             Long scheduleWarningGroupId,
                                             String scheduleProcessInstancePriority,
                                             String scheduleWorkerGroup,
                                             String scheduleTenantCode,
                                             Long scheduleEnvironmentCode,
                                             Boolean scheduleAutoOnline) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("scheduleCron", scheduleCron);
        schedule.put("scheduleTimezone", scheduleTimezone);
        schedule.put("scheduleStartTime", scheduleStartTime);
        schedule.put("scheduleEndTime", scheduleEndTime);
        schedule.put("scheduleFailureStrategy", scheduleFailureStrategy);
        schedule.put("scheduleWarningType", scheduleWarningType);
        schedule.put("scheduleWarningGroupId", scheduleWarningGroupId);
        schedule.put("scheduleProcessInstancePriority", scheduleProcessInstancePriority);
        schedule.put("scheduleWorkerGroup", scheduleWorkerGroup);
        schedule.put("scheduleTenantCode", scheduleTenantCode);
        schedule.put("scheduleEnvironmentCode", scheduleEnvironmentCode);
        schedule.put("scheduleAutoOnline", scheduleAutoOnline);
        return schedule;
    }
}
