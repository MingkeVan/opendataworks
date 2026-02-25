package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowApprovalRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncIssue;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流发布 orchestrator（Phase 1：记录 + 状态流转）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowPublishService {

    private static final String PUBLISH_DIFF_CONFIRM_REQUIRED = "PUBLISH_DIFF_CONFIRM_REQUIRED";
    private static final String PUBLISH_PREVIEW_FAILED = "PUBLISH_PREVIEW_FAILED";
    private static final String PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND = "PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND";
    private static final String PUBLISH_FIRST_DEPLOY = "PUBLISH_FIRST_DEPLOY";
    private static final DateTimeFormatter SCHEDULE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkflowPublishRecordMapper publishRecordMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DolphinRuntimeDefinitionService runtimeDefinitionService;
    private final WorkflowRuntimeDiffService runtimeDiffService;
    private final WorkflowDeployService workflowDeployService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final ObjectMapper objectMapper;

    public WorkflowPublishPreviewResponse previewPublish(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        return buildPublishPreview(workflow);
    }

    @Transactional
    public WorkflowPublishRecord publish(Long workflowId, WorkflowPublishRequest request) {
        if (!StringUtils.hasText(request.getOperation())) {
            throw new IllegalArgumentException("operation is required");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long versionId = request.getVersionId() != null ? request.getVersionId() : workflow.getCurrentVersionId();
        WorkflowVersion version = versionId == null ? null : workflowVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("Workflow version not found for publish");
        }

        WorkflowPublishRecord record = new WorkflowPublishRecord();
        record.setWorkflowId(workflowId);
        record.setVersionId(version.getId());
        record.setOperation(request.getOperation().toLowerCase());
        record.setTargetEngine("dolphin");
        record.setStatus("pending");
        record.setOperator(request.getOperator());
        publishRecordMapper.insert(record);

        try {
            log.info("Workflow {} publish operation {} initiated for version {}", workflowId, record.getOperation(),
                    version.getVersionNo());
            switch (record.getOperation()) {
                case "deploy":
                    handleDeploy(workflow, version, record, request);
                    break;
                case "online":
                case "offline":
                    invokeDolphin(workflow, record);
                    applyWorkflowStatus(workflow, record);
                    record.setStatus("success");
                    record.setEngineWorkflowCode(workflow.getWorkflowCode());
                    break;
                default:
                    log.warn("Unsupported publish operation {}", record.getOperation());
                    record.setStatus("failed");
                    break;
            }
            publishRecordMapper.updateById(record);
            dataWorkflowMapper.updateById(workflow);
            return record;
        } catch (RuntimeException ex) {
            record.setStatus("failed");
            record.setLog(toJson(Collections.singletonMap("error", ex.getMessage())));
            publishRecordMapper.updateById(record);
            workflow.setPublishStatus("failed");
            dataWorkflowMapper.updateById(workflow);
            throw ex;
        }
    }

    private WorkflowPublishPreviewResponse buildPublishPreview(DataWorkflow workflow) {
        WorkflowPublishPreviewResponse response = new WorkflowPublishPreviewResponse();
        response.setWorkflowId(workflow.getId());
        response.setProjectCode(workflow.getProjectCode());
        response.setWorkflowCode(workflow.getWorkflowCode());

        RuntimeWorkflowDefinition platformDefinition = buildPlatformDefinition(workflow);
        if (CollectionUtils.isEmpty(platformDefinition.getTasks())) {
            RuntimeSyncIssue issue = RuntimeSyncIssue.error(PUBLISH_PREVIEW_FAILED, "工作流未绑定任何任务，无法发布");
            issue.setWorkflowCode(workflow.getWorkflowCode());
            issue.setWorkflowName(workflow.getWorkflowName());
            response.getErrors().add(issue);
            response.setCanPublish(false);
            return response;
        }

        RuntimeWorkflowDefinition runtimeDefinition = null;
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            RuntimeSyncIssue warning = RuntimeSyncIssue.warning(
                    PUBLISH_FIRST_DEPLOY,
                    "Dolphin 侧尚无 workflowCode，当前发布将执行首次部署");
            warning.setWorkflowName(workflow.getWorkflowName());
            response.getWarnings().add(warning);
        } else {
            try {
                runtimeDefinition = runtimeDefinitionService.loadRuntimeDefinitionFromExport(
                        workflow.getProjectCode(),
                        workflow.getWorkflowCode());
            } catch (Exception ex) {
                if (isRuntimeWorkflowMissing(ex.getMessage())) {
                    RuntimeSyncIssue warning = RuntimeSyncIssue.warning(
                            PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND,
                            "Dolphin 侧未找到同编码工作流，将按首次部署处理: " + ex.getMessage());
                    warning.setWorkflowCode(workflow.getWorkflowCode());
                    warning.setWorkflowName(workflow.getWorkflowName());
                    response.getWarnings().add(warning);
                } else {
                    RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                            PUBLISH_PREVIEW_FAILED,
                            "读取 Dolphin 运行态定义失败: " + ex.getMessage());
                    issue.setWorkflowCode(workflow.getWorkflowCode());
                    issue.setWorkflowName(workflow.getWorkflowName());
                    response.getErrors().add(issue);
                    response.setCanPublish(false);
                    return response;
                }
            }
        }

        WorkflowRuntimeDiffService.RuntimeSnapshot platformSnapshot = runtimeDiffService.buildSnapshot(
                platformDefinition,
                inferEdgesFromLineage(platformDefinition.getTasks()));
        String baselineSnapshotJson = null;
        if (runtimeDefinition != null) {
            WorkflowRuntimeDiffService.RuntimeSnapshot runtimeSnapshot = runtimeDiffService.buildSnapshot(
                    runtimeDefinition,
                    inferEdgesFromLineage(runtimeDefinition.getTasks()));
            baselineSnapshotJson = runtimeSnapshot.getSnapshotJson();
        }
        RuntimeDiffSummary diffSummary = runtimeDiffService.buildDiff(baselineSnapshotJson, platformSnapshot);
        response.setDiffSummary(diffSummary);
        response.setRequireConfirm(diffSummary != null && Boolean.TRUE.equals(diffSummary.getChanged()));
        response.setCanPublish(response.getErrors().isEmpty());
        return response;
    }

    private RuntimeWorkflowDefinition buildPlatformDefinition(DataWorkflow workflow) {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(workflow.getProjectCode());
        definition.setWorkflowCode(workflow.getWorkflowCode());
        definition.setWorkflowName(workflow.getWorkflowName());
        definition.setDescription(workflow.getDescription());
        definition.setReleaseState(mapWorkflowReleaseState(workflow.getStatus()));
        definition.setGlobalParams(workflow.getGlobalParams());
        definition.setSchedule(buildPlatformSchedule(workflow));

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflow.getId())
                        .orderByAsc(WorkflowTaskRelation::getId));
        if (CollectionUtils.isEmpty(relations)) {
            definition.setTasks(Collections.emptyList());
            return definition;
        }

        List<Long> taskIds = relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(taskIds)) {
            definition.setTasks(Collections.emptyList());
            return definition;
        }

        Map<Long, DataTask> taskById = dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));
        Map<Long, List<Long>> inputTableIdsByTask = loadTaskTableRelationMap(taskIds, "read");
        Map<Long, List<Long>> outputTableIdsByTask = loadTaskTableRelationMap(taskIds, "write");

        List<RuntimeTaskDefinition> tasks = new ArrayList<>();
        for (WorkflowTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null) {
                continue;
            }
            DataTask task = taskById.get(relation.getTaskId());
            if (task == null) {
                continue;
            }
            RuntimeTaskDefinition runtimeTask = new RuntimeTaskDefinition();
            runtimeTask.setTaskCode(resolveTaskCodeForDiff(task));
            runtimeTask.setTaskVersion(task.getDolphinTaskVersion());
            runtimeTask.setTaskName(task.getTaskName());
            runtimeTask.setDescription(task.getTaskDesc());
            runtimeTask.setNodeType(resolveTaskNodeType(task));
            runtimeTask.setSql(task.getTaskSql());
            runtimeTask.setDatasourceName(task.getDatasourceName());
            runtimeTask.setDatasourceType(task.getDatasourceType());
            runtimeTask.setTaskGroupName(StringUtils.hasText(task.getTaskGroupName())
                    ? task.getTaskGroupName()
                    : workflow.getTaskGroupName());
            runtimeTask.setRetryTimes(task.getRetryTimes());
            runtimeTask.setRetryInterval(task.getRetryInterval());
            runtimeTask.setTimeoutSeconds(task.getTimeoutSeconds());
            if (task.getPriority() != null) {
                runtimeTask.setTaskPriority(String.valueOf(task.getPriority()));
            }
            runtimeTask.setInputTableIds(inputTableIdsByTask.getOrDefault(task.getId(), Collections.emptyList()));
            runtimeTask.setOutputTableIds(outputTableIdsByTask.getOrDefault(task.getId(), Collections.emptyList()));
            tasks.add(runtimeTask);
        }
        definition.setTasks(tasks);
        return definition;
    }

    private RuntimeWorkflowSchedule buildPlatformSchedule(DataWorkflow workflow) {
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(workflow.getDolphinScheduleId());
        schedule.setReleaseState(workflow.getScheduleState());
        schedule.setCrontab(workflow.getScheduleCron());
        schedule.setTimezoneId(workflow.getScheduleTimezone());
        schedule.setStartTime(toDateTimeText(workflow.getScheduleStartTime()));
        schedule.setEndTime(toDateTimeText(workflow.getScheduleEndTime()));
        schedule.setFailureStrategy(workflow.getScheduleFailureStrategy());
        schedule.setWarningType(workflow.getScheduleWarningType());
        schedule.setWarningGroupId(workflow.getScheduleWarningGroupId());
        schedule.setProcessInstancePriority(workflow.getScheduleProcessInstancePriority());
        schedule.setWorkerGroup(workflow.getScheduleWorkerGroup());
        schedule.setTenantCode(workflow.getScheduleTenantCode());
        schedule.setEnvironmentCode(workflow.getScheduleEnvironmentCode());
        return schedule;
    }

    private Map<Long, List<Long>> loadTaskTableRelationMap(List<Long> taskIds, String relationType) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyMap();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTaskId, taskIds)
                        .eq(TableTaskRelation::getRelationType, relationType)
                        .orderByAsc(TableTaskRelation::getTaskId)
                        .orderByAsc(TableTaskRelation::getTableId));
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyMap();
        }
        Map<Long, LinkedHashSet<Long>> grouped = new LinkedHashMap<>();
        for (TableTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null || relation.getTableId() == null) {
                continue;
            }
            grouped.computeIfAbsent(relation.getTaskId(), key -> new LinkedHashSet<>()).add(relation.getTableId());
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        grouped.forEach((taskId, tableIds) -> result.put(taskId, new ArrayList<>(tableIds)));
        return result;
    }

    private List<RuntimeTaskEdge> inferEdgesFromLineage(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        List<RuntimeTaskDefinition> sorted = tasks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());

        List<RuntimeTaskEdge> edges = new ArrayList<>();
        for (RuntimeTaskDefinition downstream : sorted) {
            if (downstream.getTaskCode() == null) {
                continue;
            }
            Set<Long> downstreamReads = new LinkedHashSet<>(downstream.getInputTableIds());
            if (downstreamReads.isEmpty()) {
                continue;
            }
            for (RuntimeTaskDefinition upstream : sorted) {
                if (upstream.getTaskCode() == null || Objects.equals(upstream.getTaskCode(), downstream.getTaskCode())) {
                    continue;
                }
                Set<Long> upstreamWrites = new LinkedHashSet<>(upstream.getOutputTableIds());
                if (upstreamWrites.isEmpty()) {
                    continue;
                }
                Set<Long> intersection = new LinkedHashSet<>(upstreamWrites);
                intersection.retainAll(downstreamReads);
                if (!intersection.isEmpty()) {
                    edges.add(new RuntimeTaskEdge(upstream.getTaskCode(), downstream.getTaskCode()));
                }
            }
        }
        return edges.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
    }

    private Long resolveTaskCodeForDiff(DataTask task) {
        if (task == null) {
            return null;
        }
        if (task.getDolphinTaskCode() != null && task.getDolphinTaskCode() > 0) {
            return task.getDolphinTaskCode();
        }
        return task.getId();
    }

    private String resolveTaskNodeType(DataTask task) {
        if (task == null) {
            return null;
        }
        if (StringUtils.hasText(task.getDolphinNodeType())) {
            return task.getDolphinNodeType();
        }
        if (StringUtils.hasText(task.getTaskType())) {
            return task.getTaskType();
        }
        return "SQL";
    }

    private String mapWorkflowReleaseState(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        if ("online".equalsIgnoreCase(status)) {
            return "ONLINE";
        }
        if ("offline".equalsIgnoreCase(status)) {
            return "OFFLINE";
        }
        return status;
    }

    private String toDateTimeText(LocalDateTime value) {
        return value != null ? value.format(SCHEDULE_TIME_FORMATTER) : null;
    }

    private boolean isRuntimeWorkflowMissing(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim().toLowerCase();
        return normalized.contains("未找到")
                || normalized.contains("not found")
                || normalized.contains("不存在");
    }

    private void applyWorkflowStatus(DataWorkflow workflow, WorkflowPublishRecord record) {
        switch (record.getOperation()) {
            case "deploy":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "online":
                workflow.setStatus("online");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "offline":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                workflow.setScheduleState("OFFLINE");
                break;
            default:
                log.warn("Unknown workflow publish operation {}", record.getOperation());
        }
    }

    private void invokeDolphin(DataWorkflow workflow, WorkflowPublishRecord record) {
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未 deploy，无法执行 " + record.getOperation());
        }
        try {
            if ("online".equals(record.getOperation())) {
                dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "ONLINE");
                tryAutoOnlineSchedule(workflow);
            } else if ("offline".equals(record.getOperation())) {
                dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "OFFLINE");
                tryAutoOfflineSchedule(workflow);
            } else {
                log.debug("No Dolphin action for operation {}", record.getOperation());
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("调用 DolphinScheduler 失败: " + ex.getMessage(), ex);
        }
    }

    private void tryAutoOnlineSchedule(DataWorkflow workflow) {
        if (!Boolean.TRUE.equals(workflow.getScheduleAutoOnline())) {
            return;
        }
        DolphinSchedule schedule = dolphinSchedulerService.getWorkflowSchedule(workflow.getWorkflowCode());
        if (schedule != null && schedule.getId() != null && schedule.getId() > 0) {
            workflow.setDolphinScheduleId(schedule.getId());
            if (StringUtils.hasText(schedule.getReleaseState())) {
                workflow.setScheduleState(schedule.getReleaseState());
            }
        }

        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            return;
        }

        boolean needOnline = true;
        if (schedule != null && StringUtils.hasText(schedule.getReleaseState())) {
            needOnline = !"ONLINE".equalsIgnoreCase(schedule.getReleaseState());
        } else if (StringUtils.hasText(workflow.getScheduleState())) {
            needOnline = !"ONLINE".equalsIgnoreCase(workflow.getScheduleState());
        }
        if (!needOnline) {
            return;
        }

        try {
            dolphinSchedulerService.onlineWorkflowSchedule(scheduleId);
            workflow.setScheduleState("ONLINE");
        } catch (Exception ex) {
            log.warn("Failed to online schedule {} for workflow {}: {}",
                    scheduleId, workflow.getWorkflowCode(), ex.getMessage());
        }
    }

    private void tryAutoOfflineSchedule(DataWorkflow workflow) {
        DolphinSchedule schedule = dolphinSchedulerService.getWorkflowSchedule(workflow.getWorkflowCode());
        if (schedule != null && schedule.getId() != null && schedule.getId() > 0) {
            workflow.setDolphinScheduleId(schedule.getId());
            if (StringUtils.hasText(schedule.getReleaseState())) {
                workflow.setScheduleState(schedule.getReleaseState());
            }
        }

        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            workflow.setScheduleState("OFFLINE");
            return;
        }
        try {
            dolphinSchedulerService.offlineWorkflowSchedule(scheduleId);
        } catch (Exception ex) {
            log.warn("Failed to offline schedule {} for workflow {}: {}",
                    scheduleId, workflow.getWorkflowCode(), ex.getMessage());
        } finally {
            workflow.setScheduleState("OFFLINE");
        }
    }

    private void handleDeploy(DataWorkflow workflow,
            WorkflowVersion version,
            WorkflowPublishRecord record,
            WorkflowPublishRequest request) {
        boolean needApproval = Boolean.TRUE.equals(request.getRequireApproval());
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        if (needApproval && !approved) {
            record.setStatus("pending_approval");
            record.setLog(toJson(Collections.singletonMap("comment", request.getApprovalComment())));
            return;
        }

        WorkflowPublishPreviewResponse preview = buildPublishPreview(workflow);
        if (!Boolean.TRUE.equals(preview.getCanPublish())) {
            RuntimeSyncIssue issue = CollectionUtils.isEmpty(preview.getErrors()) ? null : preview.getErrors().get(0);
            String detail = issue != null && StringUtils.hasText(issue.getMessage())
                    ? issue.getMessage()
                    : "发布预检失败";
            throw new IllegalStateException(PUBLISH_PREVIEW_FAILED + ": " + detail);
        }
        if (Boolean.TRUE.equals(preview.getRequireConfirm()) && !Boolean.TRUE.equals(request.getConfirmDiff())) {
            throw new IllegalStateException(PUBLISH_DIFF_CONFIRM_REQUIRED + ": 检测到平台与 Dolphin 存在变更差异，请先确认变更详情后再发布");
        }
        performDeploy(workflow, version, record);
    }

    private void performDeploy(DataWorkflow workflow,
            WorkflowVersion version,
            WorkflowPublishRecord record) {
        WorkflowDeployService.DeploymentResult result = workflowDeployService.deploy(workflow);
        workflow.setWorkflowCode(result.getWorkflowCode());
        if (result.getProjectCode() != null) {
            workflow.setProjectCode(result.getProjectCode());
        }
        applyWorkflowStatus(workflow, record);
        record.setStatus("success");
        record.setEngineWorkflowCode(result.getWorkflowCode());
        record.setLog(toJson(Collections.singletonMap("taskCount", result.getTaskCount())));
    }

    @Transactional
    public WorkflowPublishRecord approve(Long workflowId,
            Long recordId,
            WorkflowApprovalRequest request) {
        WorkflowPublishRecord record = publishRecordMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getWorkflowId(), workflowId)) {
            throw new IllegalArgumentException("发布记录不存在");
        }
        if (!"pending_approval".equals(record.getStatus())) {
            throw new IllegalStateException("当前状态不可审批: " + record.getStatus());
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        WorkflowVersion version = workflowVersionMapper.selectById(record.getVersionId());
        if (!Boolean.TRUE.equals(request.getApproved())) {
            record.setStatus("rejected");
            record.setOperator(request.getApprover());
            record.setLog(toJson(Collections.singletonMap("comment", request.getComment())));
            publishRecordMapper.updateById(record);
            return record;
        }

        record.setOperator(request.getApprover());
        performDeploy(workflow, version, record);
        publishRecordMapper.updateById(record);
        dataWorkflowMapper.updateById(workflow);
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
