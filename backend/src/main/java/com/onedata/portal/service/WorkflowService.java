package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };
    private static final int SNAPSHOT_SCHEMA_VERSION_CANONICAL = 2;

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowInstanceCacheService workflowInstanceCacheService;
    private final ObjectMapper objectMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final WorkflowTopologyService workflowTopologyService;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        LambdaQueryWrapper<DataWorkflow> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(DataWorkflow::getWorkflowName, request.getKeyword());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(DataWorkflow::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(DataWorkflow::getUpdatedAt);
        Page<DataWorkflow> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<DataWorkflow> result = dataWorkflowMapper.selectPage(page, wrapper);
        attachLatestInstanceInfo(result.getRecords());
        attachCurrentVersionInfo(result.getRecords());
        return result;
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        trySyncScheduleFromEngine(workflow);
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getCreatedAt));
        List<WorkflowVersion> versions = workflowVersionService.listByWorkflow(workflowId);
        List<WorkflowPublishRecord> publishRecords = workflowPublishRecordMapper.selectList(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt));
        workflow.setCurrentVersionNo(versions.stream()
                .filter(version -> Objects.equals(version.getId(), workflow.getCurrentVersionId()))
                .map(WorkflowVersion::getVersionNo)
                .findFirst()
                .orElse(null));
        List<WorkflowInstanceCache> recentInstances = resolveRecentInstances(workflow, 10);
        return WorkflowDetailResponse.builder()
                .workflow(workflow)
                .taskRelations(relations)
                .versions(versions)
                .publishRecords(publishRecords)
                .recentInstances(recentInstances)
                .build();
    }

    @Transactional
    public String buildDefinitionJsonForExport(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (StringUtils.hasText(workflow.getDefinitionJson())) {
            return workflow.getDefinitionJson();
        }

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (WorkflowTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null) {
                continue;
            }
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception ignored) {
                    // ignore malformed nodeAttrs for export
                }
            }
            bindings.add(binding);
        }

        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(collectTaskIds(bindings));
        String definitionJson = toJson(buildPlatformDefinitionDocument(workflow, bindings, topology));
        workflow.setDefinitionJson(definitionJson);
        dataWorkflowMapper.updateById(workflow);
        return definitionJson;
    }

    private List<WorkflowInstanceCache> resolveRecentInstances(DataWorkflow workflow, int limit) {
        if (workflow == null || workflow.getId() == null) {
            return Collections.emptyList();
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
        try {
            List<WorkflowInstanceSummary> realtimeSummaries = dolphinSchedulerService
                    .listWorkflowInstances(workflow.getWorkflowCode(), limit);
            workflowInstanceCacheService.replaceCache(workflow, realtimeSummaries);
            return mapSummariesToCaches(workflow.getId(), realtimeSummaries);
        } catch (Exception ex) {
            log.warn("Failed to fetch realtime instances for workflow {}: {}", workflow.getWorkflowName(), ex.getMessage());
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
    }

    private void attachCurrentVersionInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        Set<Long> versionIds = workflows.stream()
                .map(DataWorkflow::getCurrentVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return;
        }
        Map<Long, Integer> versionNoById = workflowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(WorkflowVersion::getId, WorkflowVersion::getVersionNo, (left, right) -> left));
        workflows.forEach(workflow -> workflow.setCurrentVersionNo(versionNoById.get(workflow.getCurrentVersionId())));
    }

    private void trySyncScheduleFromEngine(DataWorkflow workflow) {
        if (workflow == null) {
            return;
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            return;
        }

        boolean needsSync = workflow.getDolphinScheduleId() == null
                || workflow.getDolphinScheduleId() <= 0
                || !StringUtils.hasText(workflow.getScheduleState())
                || !StringUtils.hasText(workflow.getScheduleCron())
                || !StringUtils.hasText(workflow.getScheduleTimezone())
                || workflow.getScheduleStartTime() == null
                || workflow.getScheduleEndTime() == null;
        if (!needsSync) {
            return;
        }

        DolphinSchedule schedule = dolphinSchedulerService.getWorkflowSchedule(workflowCode);
        if (schedule == null || schedule.getId() == null || schedule.getId() <= 0) {
            return;
        }

        boolean changed = false;

        if (workflow.getDolphinScheduleId() == null
                || workflow.getDolphinScheduleId() <= 0
                || !Objects.equals(workflow.getDolphinScheduleId(), schedule.getId())) {
            workflow.setDolphinScheduleId(schedule.getId());
            changed = true;
        }

        if (StringUtils.hasText(schedule.getReleaseState())
                && !schedule.getReleaseState().equalsIgnoreCase(workflow.getScheduleState())) {
            workflow.setScheduleState(schedule.getReleaseState());
            changed = true;
        }

        if (!StringUtils.hasText(workflow.getScheduleCron()) && StringUtils.hasText(schedule.getCrontab())) {
            workflow.setScheduleCron(schedule.getCrontab());
            changed = true;
        }
        if (!StringUtils.hasText(workflow.getScheduleTimezone()) && StringUtils.hasText(schedule.getTimezoneId())) {
            workflow.setScheduleTimezone(schedule.getTimezoneId());
            changed = true;
        }
        if (workflow.getScheduleStartTime() == null && StringUtils.hasText(schedule.getStartTime())) {
            LocalDateTime start = parseFlexibleDateTime(schedule.getStartTime());
            if (start != null) {
                workflow.setScheduleStartTime(start);
                changed = true;
            }
        }
        if (workflow.getScheduleEndTime() == null && StringUtils.hasText(schedule.getEndTime())) {
            LocalDateTime end = parseFlexibleDateTime(schedule.getEndTime());
            if (end != null) {
                workflow.setScheduleEndTime(end);
                changed = true;
            }
        }

        if (!StringUtils.hasText(workflow.getScheduleFailureStrategy())
                && StringUtils.hasText(schedule.getFailureStrategy())) {
            workflow.setScheduleFailureStrategy(schedule.getFailureStrategy());
            changed = true;
        }
        if (!StringUtils.hasText(workflow.getScheduleWarningType()) && StringUtils.hasText(schedule.getWarningType())) {
            String warningType = schedule.getWarningType();
            if ("SUCCESS_FAILURE".equalsIgnoreCase(warningType)) {
                warningType = "ALL";
            }
            workflow.setScheduleWarningType(warningType);
            changed = true;
        }
        if (workflow.getScheduleWarningGroupId() == null) {
            workflow.setScheduleWarningGroupId(schedule.getWarningGroupId() != null ? schedule.getWarningGroupId() : 0L);
            changed = true;
        }
        if (!StringUtils.hasText(workflow.getScheduleProcessInstancePriority())
                && StringUtils.hasText(schedule.getProcessInstancePriority())) {
            workflow.setScheduleProcessInstancePriority(schedule.getProcessInstancePriority());
            changed = true;
        }
        if (!StringUtils.hasText(workflow.getScheduleWorkerGroup()) && StringUtils.hasText(schedule.getWorkerGroup())) {
            workflow.setScheduleWorkerGroup(schedule.getWorkerGroup());
            changed = true;
        }
        if (!StringUtils.hasText(workflow.getScheduleTenantCode()) && StringUtils.hasText(schedule.getTenantCode())) {
            workflow.setScheduleTenantCode(schedule.getTenantCode());
            changed = true;
        }
        if (workflow.getScheduleEnvironmentCode() == null) {
            workflow.setScheduleEnvironmentCode(schedule.getEnvironmentCode() != null ? schedule.getEnvironmentCode() : -1L);
            changed = true;
        }

        if (changed) {
            dataWorkflowMapper.updateById(workflow);
        }
    }

    @Transactional
    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        DataWorkflow workflow = new DataWorkflow();
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        dataWorkflowMapper.insert(workflow);

        persistTaskRelations(workflow.getId(), taskBindings, null, topology);

        workflow.setDefinitionJson(resolveDefinitionJson(workflow, request, taskBindings, topology));
        dataWorkflowMapper.updateById(workflow);

        Map<String, Object> snapshot = buildCanonicalSnapshot(workflow, request);
        String snapshotJson = toJson(snapshot);
        WorkflowVersion version = snapshotWorkflow(workflow, request, snapshotJson);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);

        updateRelationVersion(workflow.getId(), version.getId());
        return workflow;
    }

    public String executeWorkflow(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再执行");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String executionId = dolphinSchedulerService.startProcessInstance(
                    workflowCode,
                    null,
                    workflow.getWorkflowName());
            if (executionLog != null) {
                executionLog.setExecutionId(executionId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return executionId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (request == null) {
            throw new IllegalArgumentException("补数参数不能为空");
        }

        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再补数");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String triggerId = dolphinSchedulerService.backfillProcessInstance(workflowCode, request);
            if (executionLog != null) {
                executionLog.setExecutionId(triggerId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return triggerId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    private TaskExecutionLog createWorkflowExecutionLog(Long workflowId, String triggerType) {
        Long taskId = resolveMonitorTaskId(workflowId);
        if (taskId == null) {
            log.warn("No task relation found for workflow {}, skip execution log creation", workflowId);
            return null;
        }
        TaskExecutionLog logRecord = new TaskExecutionLog();
        logRecord.setTaskId(taskId);
        logRecord.setStatus("pending");
        logRecord.setStartTime(LocalDateTime.now());
        logRecord.setTriggerType(StringUtils.hasText(triggerType) ? triggerType : "manual");
        taskExecutionLogMapper.insert(logRecord);
        return logRecord;
    }

    private void markExecutionFailed(TaskExecutionLog executionLog, RuntimeException ex) {
        if (executionLog == null) {
            return;
        }
        executionLog.setStatus("failed");
        executionLog.setEndTime(LocalDateTime.now());
        executionLog.setErrorMessage(ex.getMessage());
        taskExecutionLogMapper.updateById(executionLog);
    }

    private Long resolveMonitorTaskId(Long workflowId) {
        if (workflowId == null) {
            return null;
        }
        WorkflowTaskRelation relation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getIsEntry)
                        .orderByAsc(WorkflowTaskRelation::getId)
                        .last("LIMIT 1"));
        return relation != null ? relation.getTaskId() : null;
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }

        persistTaskRelations(workflowId, taskBindings, workflow.getCurrentVersionId(), topology);

        workflow.setDefinitionJson(resolveDefinitionJson(workflow, request, taskBindings, topology));
        dataWorkflowMapper.updateById(workflow);

        Map<String, Object> snapshot = buildCanonicalSnapshot(workflow, request);
        String snapshotJson = toJson(snapshot);
        if (shouldCreateNewVersion(workflow, snapshotJson)) {
            WorkflowVersion version = snapshotWorkflow(workflow, request, snapshotJson);
            workflow.setCurrentVersionId(version.getId());
            dataWorkflowMapper.updateById(workflow);
            updateRelationVersion(workflowId, version.getId());
        }
        return workflow;
    }

    private void updateRelationVersion(Long workflowId, Long versionId) {
        WorkflowTaskRelation update = new WorkflowTaskRelation();
        update.setVersionId(versionId);
        workflowTaskRelationMapper.update(update,
                Wrappers.<WorkflowTaskRelation>lambdaUpdate()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow, WorkflowDefinitionRequest request) {
        Map<String, Object> snapshot = buildCanonicalSnapshot(workflow, request);
        return snapshotWorkflow(workflow, request, toJson(snapshot));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            String snapshotJson) {
        boolean isInitial = workflow.getCurrentVersionId() == null;
        String changeSummary = isInitial ? "initial workflow definition" : "updated workflow definition";
        return workflowVersionService.createVersion(
                workflow.getId(),
                snapshotJson,
                StringUtils.hasText(request.getDescription()) ? request.getDescription() : changeSummary,
                request.getTriggerSource(),
                request.getOperator(),
                SNAPSHOT_SCHEMA_VERSION_CANONICAL,
                null);
    }

    private boolean shouldCreateNewVersion(DataWorkflow workflow, String incomingSnapshotJson) {
        if (workflow == null) {
            return true;
        }
        if (workflow.getCurrentVersionId() == null) {
            return true;
        }
        WorkflowVersion currentVersion = workflowVersionMapper.selectById(workflow.getCurrentVersionId());
        if (currentVersion == null || !StringUtils.hasText(currentVersion.getStructureSnapshot())) {
            return true;
        }
        String currentHash = snapshotContentHash(currentVersion.getStructureSnapshot());
        String incomingHash = snapshotContentHash(incomingSnapshotJson);
        if (!StringUtils.hasText(currentHash) || !StringUtils.hasText(incomingHash)) {
            return true;
        }
        return !Objects.equals(currentHash, incomingHash);
    }

    private String snapshotContentHash(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (node != null && node.isObject()) {
                ((ObjectNode) node).remove("meta");
            }
            String normalized = node != null ? canonicalizeJson(node) : snapshotJson.trim();
            return sha256(normalized);
        } catch (Exception ignored) {
            return sha256(snapshotJson.trim());
        }
    }

    private String canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(fieldName).append('"').append(':');
                sb.append(canonicalizeJson(node.get(fieldName)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalizeJson(node.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return node.toString();
    }

    private String sha256(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成 hash", e);
        }
    }

    private Map<String, Object> buildCanonicalSnapshot(DataWorkflow workflow, WorkflowDefinitionRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION_CANONICAL);
        root.put("workflow", buildWorkflowSnapshotNode(workflow));
        List<Map<String, Object>> taskNodes = buildTaskSnapshotNodes(request != null ? request.getTasks() : null);
        root.put("tasks", taskNodes);
        root.put("edges", inferTaskEdges(taskNodes));
        root.put("schedule", buildScheduleSnapshotNode(workflow));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("triggerSource", request != null ? request.getTriggerSource() : null);
        meta.put("operator", request != null ? request.getOperator() : null);
        meta.put("snapshotAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        root.put("meta", meta);
        return root;
    }

    private Map<String, Object> buildWorkflowSnapshotNode(DataWorkflow workflow) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (workflow == null) {
            return node;
        }
        node.put("workflowId", workflow.getId());
        node.put("workflowCode", workflow.getWorkflowCode());
        node.put("projectCode", workflow.getProjectCode());
        node.put("workflowName", workflow.getWorkflowName());
        node.put("description", workflow.getDescription());
        node.put("definitionJson", defaultJson(workflow.getDefinitionJson()));
        node.put("globalParams", workflow.getGlobalParams());
        node.put("taskGroupName", workflow.getTaskGroupName());
        node.put("status", workflow.getStatus());
        node.put("publishStatus", workflow.getPublishStatus());
        node.put("syncSource", workflow.getSyncSource());
        return node;
    }

    private Map<String, Object> buildScheduleSnapshotNode(DataWorkflow workflow) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        if (workflow == null) {
            return schedule;
        }
        schedule.put("dolphinScheduleId", workflow.getDolphinScheduleId());
        schedule.put("scheduleState", workflow.getScheduleState());
        schedule.put("scheduleCron", workflow.getScheduleCron());
        schedule.put("scheduleTimezone", workflow.getScheduleTimezone());
        schedule.put("scheduleStartTime", toDateTimeText(workflow.getScheduleStartTime()));
        schedule.put("scheduleEndTime", toDateTimeText(workflow.getScheduleEndTime()));
        schedule.put("scheduleFailureStrategy", workflow.getScheduleFailureStrategy());
        schedule.put("scheduleWarningType", workflow.getScheduleWarningType());
        schedule.put("scheduleWarningGroupId", workflow.getScheduleWarningGroupId());
        schedule.put("scheduleProcessInstancePriority", workflow.getScheduleProcessInstancePriority());
        schedule.put("scheduleWorkerGroup", workflow.getScheduleWorkerGroup());
        schedule.put("scheduleTenantCode", workflow.getScheduleTenantCode());
        schedule.put("scheduleEnvironmentCode", workflow.getScheduleEnvironmentCode());
        schedule.put("scheduleAutoOnline", Boolean.TRUE.equals(workflow.getScheduleAutoOnline()));
        return schedule;
    }

    private String resolveDefinitionJson(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            List<WorkflowTaskBinding> taskBindings,
            WorkflowTopologyResult topology) {
        if (request != null && StringUtils.hasText(request.getDefinitionJson())) {
            return normalizeJsonText(request.getDefinitionJson());
        }
        Map<String, Object> definition = buildPlatformDefinitionDocument(workflow, taskBindings, topology);
        return toJson(definition);
    }

    private String normalizeJsonText(String jsonText) {
        if (!StringUtils.hasText(jsonText)) {
            return "{}";
        }
        String trimmed = jsonText.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private Map<String, Object> buildPlatformDefinitionDocument(DataWorkflow workflow,
            List<WorkflowTaskBinding> bindings,
            WorkflowTopologyResult topology) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("processDefinition", buildProcessDefinitionNode(workflow));
        List<Map<String, Object>> taskNodes = buildTaskSnapshotNodes(bindings);
        root.put("taskDefinitionList", buildTaskDefinitionNodes(taskNodes));
        root.put("processTaskRelationList", buildProcessTaskRelationNodes(taskNodes, topology));
        root.put("schedule", buildScheduleDefinitionNode(workflow));
        return root;
    }

    private Map<String, Object> buildProcessDefinitionNode(DataWorkflow workflow) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (workflow == null) {
            return node;
        }
        node.put("code", workflow.getWorkflowCode());
        node.put("workflowCode", workflow.getWorkflowCode());
        node.put("projectCode", workflow.getProjectCode());
        node.put("name", workflow.getWorkflowName());
        node.put("description", workflow.getDescription());
        node.put("globalParams", workflow.getGlobalParams());
        node.put("taskGroupName", workflow.getTaskGroupName());
        node.put("releaseState", workflow.getStatus());
        return node;
    }

    private List<Map<String, Object>> buildTaskDefinitionNodes(List<Map<String, Object>> taskNodes) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Map<String, Object> taskNode : taskNodes) {
            if (taskNode == null) {
                continue;
            }
            Long runtimeTaskCode = asLong(taskNode.get("dolphinTaskCode"));
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                runtimeTaskCode = asLong(taskNode.get("taskId"));
            }
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", runtimeTaskCode);
            item.put("taskCode", runtimeTaskCode);
            item.put("name", taskNode.get("taskName"));
            item.put("taskName", taskNode.get("taskName"));
            item.put("description", taskNode.get("taskDesc"));
            item.put("taskType", taskNode.get("dolphinNodeType"));
            item.put("nodeType", taskNode.get("dolphinNodeType"));
            item.put("version", taskNode.get("dolphinTaskVersion") != null ? taskNode.get("dolphinTaskVersion") : 1);
            item.put("timeout", taskNode.get("timeoutSeconds"));
            item.put("failRetryTimes", taskNode.get("retryTimes"));
            item.put("failRetryInterval", taskNode.get("retryInterval"));
            item.put("taskPriority", taskNode.get("priority"));
            item.put("taskGroupName", taskNode.get("taskGroupName"));

            Map<String, Object> taskParams = new LinkedHashMap<>();
            taskParams.put("sql", taskNode.get("taskSql"));
            taskParams.put("rawScript", taskNode.get("taskSql"));
            taskParams.put("datasourceName", taskNode.get("datasourceName"));
            taskParams.put("type", taskNode.get("datasourceType"));
            item.put("taskParams", taskParams);

            item.put("inputTableIds", taskNode.get("inputTableIds"));
            item.put("outputTableIds", taskNode.get("outputTableIds"));
            definitions.add(item);
        }
        return definitions;
    }

    private List<Map<String, Object>> buildProcessTaskRelationNodes(List<Map<String, Object>> taskNodes,
            WorkflowTopologyResult topology) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        Map<Long, Long> runtimeTaskCodeByTaskId = new LinkedHashMap<>();
        List<Long> allTaskCodes = new ArrayList<>();
        for (Map<String, Object> taskNode : taskNodes) {
            if (taskNode == null) {
                continue;
            }
            Long taskId = asLong(taskNode.get("taskId"));
            Long runtimeTaskCode = asLong(taskNode.get("dolphinTaskCode"));
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                runtimeTaskCode = taskId;
            }
            if (taskId != null && runtimeTaskCode != null && runtimeTaskCode > 0) {
                runtimeTaskCodeByTaskId.put(taskId, runtimeTaskCode);
                allTaskCodes.add(runtimeTaskCode);
            }
        }
        if (runtimeTaskCodeByTaskId.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> edgeSet = new LinkedHashSet<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        List<Map<String, Object>> inferredEdges = inferTaskEdges(taskNodes);
        for (Map<String, Object> edge : inferredEdges) {
            Long upstreamTaskId = asLong(edge.get("upstreamTaskId"));
            Long downstreamTaskId = asLong(edge.get("downstreamTaskId"));
            Long preTaskCode = runtimeTaskCodeByTaskId.get(upstreamTaskId);
            Long postTaskCode = runtimeTaskCodeByTaskId.get(downstreamTaskId);
            if (preTaskCode == null || postTaskCode == null || postTaskCode <= 0) {
                continue;
            }
            addRelationNode(relations, edgeSet, preTaskCode, postTaskCode);
        }

        Set<Long> entryCodes = new LinkedHashSet<>();
        if (topology != null && !CollectionUtils.isEmpty(topology.getEntryTaskIds())) {
            for (Long entryTaskId : topology.getEntryTaskIds()) {
                Long entryCode = runtimeTaskCodeByTaskId.get(entryTaskId);
                if (entryCode != null && entryCode > 0) {
                    entryCodes.add(entryCode);
                }
            }
        }
        if (entryCodes.isEmpty()) {
            Set<Long> downstreamWithUpstream = relations.stream()
                    .map(item -> asLong(item.get("postTaskCode")))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Long taskCode : allTaskCodes) {
                if (!downstreamWithUpstream.contains(taskCode)) {
                    entryCodes.add(taskCode);
                }
            }
        }
        for (Long entryCode : entryCodes) {
            addRelationNode(relations, edgeSet, 0L, entryCode);
        }
        relations.sort(Comparator
                .comparing((Map<String, Object> item) -> asLong(item.get("preTaskCode")), Comparator.nullsLast(Long::compareTo))
                .thenComparing(item -> asLong(item.get("postTaskCode")), Comparator.nullsLast(Long::compareTo)));
        return relations;
    }

    private void addRelationNode(List<Map<String, Object>> relations,
            Set<String> edgeSet,
            Long preTaskCode,
            Long postTaskCode) {
        if (postTaskCode == null || postTaskCode <= 0) {
            return;
        }
        Long normalizedPre = preTaskCode == null ? 0L : preTaskCode;
        if (normalizedPre < 0) {
            return;
        }
        String key = normalizedPre + "->" + postTaskCode;
        if (!edgeSet.add(key)) {
            return;
        }
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("preTaskCode", normalizedPre);
        relation.put("postTaskCode", postTaskCode);
        relations.add(relation);
    }

    private Map<String, Object> buildScheduleDefinitionNode(DataWorkflow workflow) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        if (workflow == null) {
            return schedule;
        }
        schedule.put("id", workflow.getDolphinScheduleId());
        schedule.put("releaseState", workflow.getScheduleState());
        schedule.put("crontab", workflow.getScheduleCron());
        schedule.put("timezoneId", workflow.getScheduleTimezone());
        schedule.put("startTime", toDateTimeText(workflow.getScheduleStartTime()));
        schedule.put("endTime", toDateTimeText(workflow.getScheduleEndTime()));
        schedule.put("failureStrategy", workflow.getScheduleFailureStrategy());
        schedule.put("warningType", workflow.getScheduleWarningType());
        schedule.put("warningGroupId", workflow.getScheduleWarningGroupId());
        schedule.put("processInstancePriority", workflow.getScheduleProcessInstancePriority());
        schedule.put("workerGroup", workflow.getScheduleWorkerGroup());
        schedule.put("tenantCode", workflow.getScheduleTenantCode());
        schedule.put("environmentCode", workflow.getScheduleEnvironmentCode());
        return schedule;
    }

    private List<Map<String, Object>> buildTaskSnapshotNodes(List<WorkflowTaskBinding> bindings) {
        List<Long> taskIds = collectTaskIds(bindings);
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyList();
        }

        List<DataTask> taskRows = dataTaskMapper.selectBatchIds(taskIds);
        Map<Long, DataTask> taskById = taskRows.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));

        Map<Long, WorkflowTaskBinding> bindingByTaskId = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(bindings)) {
            for (WorkflowTaskBinding binding : bindings) {
                if (binding == null || binding.getTaskId() == null) {
                    continue;
                }
                bindingByTaskId.putIfAbsent(binding.getTaskId(), binding);
            }
        }

        Map<Long, List<Long>> readTablesByTask = loadTaskTableRelationMap(taskIds, "read");
        Map<Long, List<Long>> writeTablesByTask = loadTaskTableRelationMap(taskIds, "write");

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Long taskId : taskIds) {
            DataTask task = taskById.get(taskId);
            if (task == null) {
                continue;
            }
            WorkflowTaskBinding binding = bindingByTaskId.get(taskId);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("taskId", task.getId());
            node.put("taskCode", task.getTaskCode());
            node.put("taskName", task.getTaskName());
            node.put("taskType", task.getTaskType());
            node.put("engine", task.getEngine());
            node.put("dolphinNodeType", task.getDolphinNodeType());
            node.put("taskSql", normalizeSql(task.getTaskSql()));
            node.put("taskDesc", task.getTaskDesc());
            node.put("datasourceName", task.getDatasourceName());
            node.put("datasourceType", task.getDatasourceType());
            node.put("taskGroupName", task.getTaskGroupName());
            node.put("retryTimes", task.getRetryTimes());
            node.put("retryInterval", task.getRetryInterval());
            node.put("timeoutSeconds", task.getTimeoutSeconds());
            node.put("priority", task.getPriority());
            node.put("dolphinTaskCode", task.getDolphinTaskCode());
            node.put("dolphinTaskVersion", task.getDolphinTaskVersion());
            node.put("inputTableIds", readTablesByTask.getOrDefault(taskId, Collections.emptyList()));
            node.put("outputTableIds", writeTablesByTask.getOrDefault(taskId, Collections.emptyList()));
            node.put("entry", binding != null ? binding.getEntry() : null);
            node.put("exit", binding != null ? binding.getExit() : null);
            node.put("nodeAttrs", binding != null ? binding.getNodeAttrs() : null);
            nodes.add(node);
        }
        return nodes;
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

    private List<Map<String, Object>> inferTaskEdges(List<Map<String, Object>> taskNodes) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sorted = taskNodes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(item -> asLong(item.get("taskId")), Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());
        Set<String> edgeSet = new LinkedHashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> downstream : sorted) {
            Long downstreamTaskId = asLong(downstream.get("taskId"));
            Set<Long> downstreamReads = new LinkedHashSet<>(toLongList(downstream.get("inputTableIds")));
            if (downstreamTaskId == null || downstreamReads.isEmpty()) {
                continue;
            }
            for (Map<String, Object> upstream : sorted) {
                Long upstreamTaskId = asLong(upstream.get("taskId"));
                if (upstreamTaskId == null || Objects.equals(upstreamTaskId, downstreamTaskId)) {
                    continue;
                }
                Set<Long> upstreamWrites = new LinkedHashSet<>(toLongList(upstream.get("outputTableIds")));
                if (upstreamWrites.isEmpty()) {
                    continue;
                }
                Set<Long> intersection = new LinkedHashSet<>(upstreamWrites);
                intersection.retainAll(downstreamReads);
                if (intersection.isEmpty()) {
                    continue;
                }
                String edgeKey = upstreamTaskId + "->" + downstreamTaskId;
                if (edgeSet.add(edgeKey)) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("upstreamTaskId", upstreamTaskId);
                    edge.put("downstreamTaskId", downstreamTaskId);
                    edges.add(edge);
                }
            }
        }
        edges.sort(Comparator
                .comparing((Map<String, Object> edge) -> asLong(edge.get("upstreamTaskId")), Comparator.nullsLast(Long::compareTo))
                .thenComparing(edge -> asLong(edge.get("downstreamTaskId")), Comparator.nullsLast(Long::compareTo)));
        return edges;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> source = (List<?>) value;
        if (source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : source) {
            Long converted = asLong(item);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }

    private String normalizeSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }
        return sql.replace("\r\n", "\n").trim();
    }

    private String toDateTimeText(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void persistTaskRelations(Long workflowId,
            List<WorkflowTaskBinding> tasks,
            Long previousVersionId,
            WorkflowTopologyResult topology) {
        workflowTaskRelationMapper.delete(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        Set<Long> entrySet = topology != null && topology.getEntryTaskIds() != null
                ? topology.getEntryTaskIds()
                : Collections.emptySet();
        Set<Long> exitSet = topology != null && topology.getExitTaskIds() != null
                ? topology.getExitTaskIds()
                : Collections.emptySet();
        for (WorkflowTaskBinding binding : tasks) {
            if (binding.getTaskId() == null) {
                continue;
            }
            ensureTaskAssignable(binding.getTaskId(), workflowId);
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(binding.getTaskId());
            relation.setIsEntry(entrySet.contains(binding.getTaskId()));
            relation.setIsExit(exitSet.contains(binding.getTaskId()));
            relation.setNodeAttrs(toJson(binding.getNodeAttrs()));
            relation.setVersionId(previousVersionId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(binding.getTaskId()));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(binding.getTaskId()));
            workflowTaskRelationMapper.insert(relation);
        }
    }

    /**
     * 重新计算工作流中所有任务的上下游关系
     * 用于在单个任务被添加/更新/删除后重新计算整个工作流的关系
     */
    public void refreshTaskRelations(Long workflowId) {
        // 获取工作流中的所有任务
        List<WorkflowTaskRelation> existingRelations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));

        // 转换为 List<WorkflowTask bindings>，保留必要的属性
        List<WorkflowTaskBinding> taskBindings = new ArrayList<>();
        Long versionId = null;
        WorkflowTopologyResult topology = null;

        for (WorkflowTaskRelation relation : existingRelations) {
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            // 将原来的 nodeAttrs 转换回 NodeAttrs
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception ex) {
                    // 忽略解析错误，使用空值
                }
            }
            taskBindings.add(binding);
            versionId = relation.getVersionId();
        }

        // 重新构建拓扑信息
        if (!taskBindings.isEmpty()) {
            List<Long> taskIds = taskBindings.stream()
                    .map(WorkflowTaskBinding::getTaskId)
                    .collect(Collectors.toList());
            topology = workflowTopologyService.buildTopology(taskIds);
        }

        // 重新保存所有关系（会先删除再插入）
        persistTaskRelations(workflowId, taskBindings, versionId, topology);
    }

    private List<Long> orderTaskIds(Set<Long> sourceIds, List<Long> taskOrder) {
        if (CollectionUtils.isEmpty(sourceIds) || CollectionUtils.isEmpty(taskOrder)) {
            return CollectionUtils.isEmpty(sourceIds) ? Collections.emptyList() : new ArrayList<>(sourceIds);
        }
        List<Long> ordered = new ArrayList<>();
        taskOrder.forEach(taskId -> {
            if (sourceIds.contains(taskId)) {
                ordered.add(taskId);
            }
        });
        if (ordered.size() < sourceIds.size()) {
            sourceIds.stream()
                    .filter(id -> !ordered.contains(id))
                    .forEach(ordered::add);
        }
        return ordered;
    }

    private List<Long> collectTaskIds(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (WorkflowTaskBinding task : tasks) {
            if (task != null && task.getTaskId() != null) {
                ordered.add(task.getTaskId());
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<WorkflowTaskBinding> normalizeTaskBindings(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize json", e);
        }
    }

    private void ensureTaskAssignable(Long taskId, Long workflowId) {
        DataTask dataTask = dataTaskMapper.selectById(taskId);
        if (dataTask == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        WorkflowTaskRelation existing = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, taskId));
        if (existing != null && !existing.getWorkflowId().equals(workflowId)) {
            throw new IllegalStateException("任务已归属其他工作流, taskId=" + taskId);
        }
    }

    private String defaultJson(String definitionJson) {
        return StringUtils.hasText(definitionJson) ? definitionJson : "{}";
    }

    private Long resolveProjectCode(Long requestProjectCode) {
        if (requestProjectCode != null && requestProjectCode > 0) {
            return requestProjectCode;
        }
        return dolphinSchedulerService.getProjectCode();
    }

    private void attachLatestInstanceInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        for (DataWorkflow workflow : workflows) {
            if (workflow.getId() == null) {
                continue;
            }
            WorkflowInstanceCache latest = null;
            boolean realtimeLoaded = false;
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                            .listWorkflowInstances(workflow.getWorkflowCode(), 1);
                    realtimeLoaded = true;
                    if (!summaries.isEmpty()) {
                        latest = mapSummaryToCache(workflow.getId(), summaries.get(0));
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch latest realtime instance for workflow {}: {}",
                            workflow.getWorkflowName(), ex.getMessage());
                }
            }
            if (latest == null && !realtimeLoaded) {
                latest = workflowInstanceCacheService.findLatest(workflow.getId());
            }
            if (latest != null) {
                applyInstance(
                        workflow,
                        latest.getInstanceId(),
                        latest.getState(),
                        latest.getStartTime(),
                        latest.getEndTime());
            }
        }
    }

    private void applyInstance(DataWorkflow workflow,
            Long instanceId,
            String state,
            Object start,
            Object end) {
        workflow.setLatestInstanceId(instanceId);
        workflow.setLatestInstanceState(state);
        workflow.setLatestInstanceStartTime(toLocalDateTime(start));
        workflow.setLatestInstanceEndTime(toLocalDateTime(end));
    }

    private LocalDateTime toLocalDateTime(Object temporal) {
        if (temporal == null) {
            return null;
        }
        if (temporal instanceof LocalDateTime) {
            return (LocalDateTime) temporal;
        }
        if (temporal instanceof Date) {
            return ((Date) temporal).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (temporal instanceof String) {
            String value = (String) temporal;
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return parseFlexibleDateTime(value);
            } catch (DateTimeParseException ignore) {
                return null;
            }
        }
        return null;
    }

    private List<WorkflowInstanceCache> mapSummariesToCaches(Long workflowId,
            List<WorkflowInstanceSummary> summaries) {
        if (workflowId == null || CollectionUtils.isEmpty(summaries)) {
            return Collections.emptyList();
        }
        return summaries.stream()
                .map(summary -> mapSummaryToCache(workflowId, summary))
                .collect(Collectors.toList());
    }

    private WorkflowInstanceCache mapSummaryToCache(Long workflowId, WorkflowInstanceSummary summary) {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflowId);
        cache.setInstanceId(summary.getInstanceId());
        cache.setState(summary.getState());
        cache.setTriggerType(summary.getCommandType());
        cache.setDurationMs(summary.getDurationMs());
        cache.setStartTime(parseToDate(summary.getStartTime()));
        cache.setEndTime(parseToDate(summary.getEndTime()));
        cache.setExtra(summary.getRawJson());
        return cache;
    }

    private Date parseToDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            LocalDateTime ldt = parseFlexibleDateTime(text);
            if (ldt == null) {
                return null;
            }
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        String candidate = raw.replace("Z", "");
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        return null;
    }

    /**
     * 删除工作流
     * 只删除工作流相关数据，保留任务定义以便复用
     */
    @Transactional
    public void deleteWorkflow(Long workflowId) {
        if (workflowId == null) {
            throw new IllegalArgumentException("工作流ID不能为空");
        }

        // 查询工作流信息
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("工作流不存在: {}", workflowId);
            return;
        }

        log.info("开始删除工作流: {}, code: {}", workflowId, workflow.getWorkflowCode());

        try {
            // Step 1: 删除DolphinScheduler中的工作流定义
            if (workflow.getWorkflowCode() != null) {
                try {
                    if (workflow.getDolphinScheduleId() != null && workflow.getDolphinScheduleId() > 0) {
                        try {
                            dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinScheduleId());
                        } catch (Exception ex) {
                            log.warn("Failed to offline schedule {} before workflow delete: {}",
                                    workflow.getDolphinScheduleId(), ex.getMessage());
                        }
                    }
                    dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "OFFLINE");
                    dolphinSchedulerService.deleteWorkflow(workflow.getWorkflowCode());
                    log.info("已删除DolphinScheduler中的工作流定义: {}", workflow.getWorkflowCode());
                } catch (Exception e) {
                    log.warn("删除DolphinScheduler工作流定义失败: {}", e.getMessage());
                    // 继续清理其他数据
                }
            }

            // Step 2: 删除工作流任务关联关系
            workflowTaskRelationMapper.delete(
                    new LambdaQueryWrapper<WorkflowTaskRelation>()
                            .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
            log.info("已删除工作流任务关联关系");

            // Step 3: 删除工作流版本记录
            workflowVersionService.deleteByWorkflowId(workflowId);
            log.info("已删除工作流版本记录");

            // Step 4: 删除工作流发布记录
            workflowPublishRecordMapper.delete(
                    new LambdaQueryWrapper<WorkflowPublishRecord>()
                            .eq(WorkflowPublishRecord::getWorkflowId, workflowId));
            log.info("已删除工作流发布记录");

            // Step 5: 删除工作流执行历史缓存
            workflowInstanceCacheService.deleteByWorkflowId(workflowId);
            log.info("已删除工作流执行历史缓存");

            // Step 6: 删除工作流定义本身
            dataWorkflowMapper.deleteById(workflowId);
            log.info("已删除工作流定义: {}", workflowId);

            log.info("工作流删除完成: {}", workflowId);
        } catch (Exception e) {
            log.error("删除工作流失败: {}", workflowId, e);
            throw new RuntimeException("删除工作流失败: " + e.getMessage(), e);
        }
    }
}
