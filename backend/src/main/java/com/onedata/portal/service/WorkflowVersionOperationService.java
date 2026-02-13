package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDiffSection;
import com.onedata.portal.dto.workflow.WorkflowVersionDiffSummary;
import com.onedata.portal.dto.workflow.WorkflowVersionErrorCodes;
import com.onedata.portal.dto.workflow.WorkflowVersionDeleteResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackResponse;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流版本比对与回退服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowVersionOperationService {

    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;
    private final DataTaskService dataTaskService;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public WorkflowVersionCompareResponse compare(Long workflowId, WorkflowVersionCompareRequest request) {
        Long rightVersionId = request != null ? request.getRightVersionId() : null;
        Long leftVersionId = request != null ? request.getLeftVersionId() : null;

        if (rightVersionId == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID, "rightVersionId 不能为空");
        }

        if (leftVersionId != null && leftVersionId.equals(rightVersionId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID, "leftVersionId 与 rightVersionId 不能相同");
        }

        if (leftVersionId != null && leftVersionId > rightVersionId) {
            Long tmp = leftVersionId;
            leftVersionId = rightVersionId;
            rightVersionId = tmp;
        }

        WorkflowVersion rightVersion = requireVersion(workflowId, rightVersionId);
        WorkflowVersion leftVersion = leftVersionId == null ? null : requireVersion(workflowId, leftVersionId);

        SnapshotNormalized left = leftVersion == null ? SnapshotNormalized.empty() : normalizeSnapshot(leftVersion);
        SnapshotNormalized right = normalizeSnapshot(rightVersion);

        WorkflowVersionCompareResponse response = new WorkflowVersionCompareResponse();
        response.setLeftVersionId(leftVersion != null ? leftVersion.getId() : null);
        response.setLeftVersionNo(leftVersion != null ? leftVersion.getVersionNo() : null);
        response.setRightVersionId(rightVersion.getId());
        response.setRightVersionNo(rightVersion.getVersionNo());

        compareFlatFields(left.getWorkflow(), right.getWorkflow(), response.getAdded().getWorkflowFields(),
                response.getRemoved().getWorkflowFields(), response.getModified().getWorkflowFields(),
                response.getUnchanged().getWorkflowFields(), "workflow");

        compareTasks(left.getTasks(), right.getTasks(), response.getAdded().getTasks(), response.getRemoved().getTasks(),
                response.getModified().getTasks(), response.getUnchanged().getTasks());

        compareEdgeSets(left.getEdges(), right.getEdges(), response.getAdded().getEdges(), response.getRemoved().getEdges(),
                response.getUnchanged().getEdges());

        compareFlatFields(left.getSchedule(), right.getSchedule(), response.getAdded().getSchedules(),
                response.getRemoved().getSchedules(), response.getModified().getSchedules(),
                response.getUnchanged().getSchedules(), "schedule");

        WorkflowVersionDiffSummary summary = response.getSummary();
        summary.setAdded(totalCount(response.getAdded()));
        summary.setRemoved(totalCount(response.getRemoved()));
        summary.setModified(totalCount(response.getModified()));
        summary.setUnchanged(totalCount(response.getUnchanged()));
        response.setChanged(summary.getAdded() > 0 || summary.getRemoved() > 0 || summary.getModified() > 0);
        response.setRawDiff(buildUnifiedRawDiff(left.getRoot(), right.getRoot(), leftVersion, rightVersion));
        return response;
    }

    @Transactional
    public WorkflowVersionRollbackResponse rollback(Long workflowId,
                                                    Long targetVersionId,
                                                    WorkflowVersionRollbackRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND, "工作流不存在: " + workflowId);
        }

        WorkflowVersion targetVersion = requireVersion(workflowId, targetVersionId);
        SnapshotNormalized target = normalizeSnapshot(targetVersion);
        if (!target.isRollbackSupported()) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "旧版本快照结构不支持完整回退，请选择较新的版本");
        }

        String operator = resolveOperator(request != null ? request.getOperator() : null);
        restoreWorkflowRuntimeFields(workflowId, target.getWorkflow(), target.getSchedule(), operator);

        List<WorkflowTaskBinding> bindings = restoreTasks(workflowId, target, operator);
        if (CollectionUtils.isEmpty(bindings)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED, "目标版本快照不包含任务定义");
        }

        WorkflowDefinitionRequest definitionRequest = new WorkflowDefinitionRequest();
        definitionRequest.setWorkflowName(readText(target.getWorkflow(), "workflowName", workflow.getWorkflowName()));
        definitionRequest.setDescription(readText(target.getWorkflow(), "description", workflow.getDescription()));
        definitionRequest.setGlobalParams(readText(target.getWorkflow(), "globalParams", workflow.getGlobalParams()));
        definitionRequest.setTaskGroupName(readText(target.getWorkflow(), "taskGroupName", workflow.getTaskGroupName()));
        definitionRequest.setProjectCode(workflow.getProjectCode());
        definitionRequest.setTasks(bindings);
        definitionRequest.setOperator(operator);
        definitionRequest.setTriggerSource("version_rollback");

        DataWorkflow updated = workflowService.updateWorkflow(workflowId, definitionRequest);
        WorkflowVersion newVersion = workflowVersionMapper.selectById(updated.getCurrentVersionId());
        if (newVersion == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_ROLLBACK_FAILED, "回退后未生成新版本");
        }
        newVersion.setRollbackFromVersionId(targetVersion.getId());
        workflowVersionMapper.updateById(newVersion);

        WorkflowVersionRollbackResponse response = new WorkflowVersionRollbackResponse();
        response.setWorkflowId(workflowId);
        response.setNewVersionId(newVersion.getId());
        response.setNewVersionNo(newVersion.getVersionNo());
        response.setRollbackFromVersionId(targetVersion.getId());
        response.setRollbackFromVersionNo(targetVersion.getVersionNo());
        return response;
    }

    @Transactional
    public WorkflowVersionDeleteResponse deleteVersion(Long workflowId, Long versionId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND, "工作流不存在: " + workflowId);
        }

        WorkflowVersion targetVersion = requireVersion(workflowId, versionId);
        Long lastSuccessfulPublishedVersionId = resolveLastSuccessfulPublishedVersionId(workflowId);
        boolean isCurrentVersion = Objects.equals(workflow.getCurrentVersionId(), versionId);

        if (isCurrentVersion) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN, "当前版本不可删除");
        }
        if (Objects.equals(lastSuccessfulPublishedVersionId, versionId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN, "最后一次成功发布版本不可删除");
        }

        workflowPublishRecordMapper.delete(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .eq(WorkflowPublishRecord::getVersionId, versionId));

        workflowRuntimeSyncRecordMapper.update(
                null,
                Wrappers.<WorkflowRuntimeSyncRecord>lambdaUpdate()
                        .eq(WorkflowRuntimeSyncRecord::getWorkflowId, workflowId)
                        .eq(WorkflowRuntimeSyncRecord::getVersionId, versionId)
                        .set(WorkflowRuntimeSyncRecord::getVersionId, null));

        workflowVersionMapper.update(
                null,
                Wrappers.<WorkflowVersion>lambdaUpdate()
                        .eq(WorkflowVersion::getWorkflowId, workflowId)
                        .eq(WorkflowVersion::getRollbackFromVersionId, versionId)
                        .set(WorkflowVersion::getRollbackFromVersionId, null));

        int deleted = workflowVersionMapper.delete(
                Wrappers.<WorkflowVersion>lambdaQuery()
                        .eq(WorkflowVersion::getId, versionId)
                        .eq(WorkflowVersion::getWorkflowId, workflowId));
        if (deleted <= 0) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FAILED, "删除版本失败: " + versionId);
        }

        WorkflowVersionDeleteResponse response = new WorkflowVersionDeleteResponse();
        response.setWorkflowId(workflowId);
        response.setDeletedVersionId(versionId);
        response.setDeletedVersionNo(targetVersion.getVersionNo());
        return response;
    }

    private List<WorkflowTaskBinding> restoreTasks(Long workflowId, SnapshotNormalized target, String operator) {
        List<TaskSnapshot> taskSnapshots = target.getTaskSnapshots();
        if (CollectionUtils.isEmpty(taskSnapshots)) {
            return Collections.emptyList();
        }

        List<Long> taskIds = taskSnapshots.stream()
                .map(TaskSnapshot::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, DataTask> existsById = dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));

        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (TaskSnapshot taskSnapshot : taskSnapshots) {
            Long taskId = taskSnapshot.getTaskId();
            if (taskId == null) {
                throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED, "快照任务缺少 taskId");
            }
            DataTask existing = existsById.get(taskId);
            if (existing == null) {
                throw badRequest(WorkflowVersionErrorCodes.VERSION_TASK_NOT_FOUND,
                        "回退任务不存在: taskId=" + taskId);
            }

            DataTask payload = new DataTask();
            payload.setId(taskId);
            payload.setTaskName(readText(taskSnapshot.getNode(), "taskName", existing.getTaskName()));
            payload.setTaskCode(readText(taskSnapshot.getNode(), "taskCode", existing.getTaskCode()));
            payload.setTaskType(readText(taskSnapshot.getNode(), "taskType", existing.getTaskType()));
            payload.setEngine(readText(taskSnapshot.getNode(), "engine", existing.getEngine()));
            payload.setDolphinNodeType(readText(taskSnapshot.getNode(), "dolphinNodeType", existing.getDolphinNodeType()));
            payload.setTaskSql(readText(taskSnapshot.getNode(), "taskSql", existing.getTaskSql()));
            payload.setTaskDesc(readText(taskSnapshot.getNode(), "taskDesc", existing.getTaskDesc()));
            payload.setDatasourceName(readText(taskSnapshot.getNode(), "datasourceName", existing.getDatasourceName()));
            payload.setDatasourceType(readText(taskSnapshot.getNode(), "datasourceType", existing.getDatasourceType()));
            payload.setTaskGroupName(readText(taskSnapshot.getNode(), "taskGroupName", existing.getTaskGroupName()));
            payload.setRetryTimes(readInteger(taskSnapshot.getNode(), "retryTimes", existing.getRetryTimes()));
            payload.setRetryInterval(readInteger(taskSnapshot.getNode(), "retryInterval", existing.getRetryInterval()));
            payload.setTimeoutSeconds(readInteger(taskSnapshot.getNode(), "timeoutSeconds", existing.getTimeoutSeconds()));
            payload.setPriority(readInteger(taskSnapshot.getNode(), "priority", existing.getPriority()));
            payload.setOwner(StringUtils.hasText(existing.getOwner()) ? existing.getOwner() : operator);
            payload.setDolphinProcessCode(existing.getDolphinProcessCode());
            payload.setDolphinTaskCode(readLong(taskSnapshot.getNode(), "dolphinTaskCode", existing.getDolphinTaskCode()));
            payload.setDolphinTaskVersion(readInteger(taskSnapshot.getNode(), "dolphinTaskVersion", existing.getDolphinTaskVersion()));
            payload.setWorkflowId(workflowId);

            dataTaskService.update(payload, taskSnapshot.getInputTableIds(), taskSnapshot.getOutputTableIds());

            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(taskId);
            binding.setEntry(readBoolean(taskSnapshot.getNode(), "entry", null));
            binding.setExit(readBoolean(taskSnapshot.getNode(), "exit", null));
            JsonNode nodeAttrs = taskSnapshot.getNode().get("nodeAttrs");
            if (nodeAttrs != null && !nodeAttrs.isNull()) {
                binding.setNodeAttrs(objectMapper.convertValue(nodeAttrs, Map.class));
            }
            bindings.add(binding);
        }
        return bindings;
    }

    private void restoreWorkflowRuntimeFields(Long workflowId,
                                              JsonNode workflowNode,
                                              JsonNode scheduleNode,
                                              String operator) {
        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflowId)
                        .set(DataWorkflow::getStatus, readText(workflowNode, "status", null))
                        .set(DataWorkflow::getPublishStatus, readText(workflowNode, "publishStatus", null))
                        .set(DataWorkflow::getSyncSource, readText(workflowNode, "syncSource", null))
                        .set(DataWorkflow::getDolphinScheduleId, readLong(scheduleNode, "dolphinScheduleId", null))
                        .set(DataWorkflow::getScheduleState, readText(scheduleNode, "scheduleState", null))
                        .set(DataWorkflow::getScheduleCron, readText(scheduleNode, "scheduleCron", null))
                        .set(DataWorkflow::getScheduleTimezone, readText(scheduleNode, "scheduleTimezone", null))
                        .set(DataWorkflow::getScheduleStartTime, readDateTime(scheduleNode, "scheduleStartTime", null))
                        .set(DataWorkflow::getScheduleEndTime, readDateTime(scheduleNode, "scheduleEndTime", null))
                        .set(DataWorkflow::getScheduleFailureStrategy, readText(scheduleNode, "scheduleFailureStrategy", null))
                        .set(DataWorkflow::getScheduleWarningType, readText(scheduleNode, "scheduleWarningType", null))
                        .set(DataWorkflow::getScheduleWarningGroupId, readLong(scheduleNode, "scheduleWarningGroupId", null))
                        .set(DataWorkflow::getScheduleProcessInstancePriority,
                                readText(scheduleNode, "scheduleProcessInstancePriority", null))
                        .set(DataWorkflow::getScheduleWorkerGroup, readText(scheduleNode, "scheduleWorkerGroup", null))
                        .set(DataWorkflow::getScheduleTenantCode, readText(scheduleNode, "scheduleTenantCode", null))
                        .set(DataWorkflow::getScheduleEnvironmentCode, readLong(scheduleNode, "scheduleEnvironmentCode", null))
                        .set(DataWorkflow::getScheduleAutoOnline, readBoolean(scheduleNode, "scheduleAutoOnline", null))
                        .set(DataWorkflow::getUpdatedBy, operator)
                        .set(DataWorkflow::getUpdatedAt, LocalDateTime.now()));
    }

    private void compareTasks(Map<String, JsonNode> left,
                              Map<String, JsonNode> right,
                              List<String> added,
                              List<String> removed,
                              List<String> modified,
                              List<String> unchanged) {
        Set<String> leftKeys = new LinkedHashSet<>(left.keySet());
        Set<String> rightKeys = new LinkedHashSet<>(right.keySet());

        for (String key : rightKeys) {
            if (!leftKeys.contains(key)) {
                added.add(describeTask(right.get(key), key));
            }
        }
        for (String key : leftKeys) {
            if (!rightKeys.contains(key)) {
                removed.add(describeTask(left.get(key), key));
            }
        }
        for (String key : leftKeys) {
            if (!rightKeys.contains(key)) {
                continue;
            }
            JsonNode leftNode = left.get(key);
            JsonNode rightNode = right.get(key);
            if (Objects.equals(leftNode, rightNode)) {
                unchanged.add(describeTask(rightNode, key));
            } else {
                modified.add(describeTask(rightNode, key));
            }
        }
    }

    private void compareEdgeSets(Set<String> left,
                                 Set<String> right,
                                 List<String> added,
                                 List<String> removed,
                                 List<String> unchanged) {
        for (String edge : right) {
            if (!left.contains(edge)) {
                added.add(edge);
            }
        }
        for (String edge : left) {
            if (!right.contains(edge)) {
                removed.add(edge);
            }
        }
        for (String edge : left) {
            if (right.contains(edge)) {
                unchanged.add(edge);
            }
        }
    }

    private void compareFlatFields(JsonNode left,
                                   JsonNode right,
                                   List<String> added,
                                   List<String> removed,
                                   List<String> modified,
                                   List<String> unchanged,
                                   String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fieldNames(left));
        keys.addAll(fieldNames(right));

        List<String> orderedKeys = new ArrayList<>(keys);
        Collections.sort(orderedKeys);
        for (String key : orderedKeys) {
            JsonNode leftValue = left != null ? left.get(key) : null;
            JsonNode rightValue = right != null ? right.get(key) : null;
            if (leftValue == null || leftValue.isNull()) {
                if (rightValue != null && !rightValue.isNull()) {
                    added.add(prefix + "." + key + " = " + toText(rightValue));
                }
                continue;
            }
            if (rightValue == null || rightValue.isNull()) {
                removed.add(prefix + "." + key + " = " + toText(leftValue));
                continue;
            }
            if (Objects.equals(leftValue, rightValue)) {
                unchanged.add(prefix + "." + key);
            } else {
                modified.add(prefix + "." + key + ": " + toText(leftValue) + " -> " + toText(rightValue));
            }
        }
    }

    private int totalCount(WorkflowVersionDiffSection section) {
        return section.getWorkflowFields().size()
                + section.getTasks().size()
                + section.getEdges().size()
                + section.getSchedules().size();
    }

    private String buildUnifiedRawDiff(JsonNode leftRoot,
                                       JsonNode rightRoot,
                                       WorkflowVersion leftVersion,
                                       WorkflowVersion rightVersion) {
        String leftText = toPrettyJson(leftRoot);
        String rightText = toPrettyJson(rightRoot);

        List<String> leftLines = Arrays.asList(leftText.split("\\R", -1));
        List<String> rightLines = Arrays.asList(rightText.split("\\R", -1));
        int[][] lcs = buildLcsMatrix(leftLines, rightLines);
        LinkedList<String> diffLines = buildDiffLines(leftLines, rightLines, lcs);

        String leftLabel = leftVersion != null && leftVersion.getVersionNo() != null
                ? "v" + leftVersion.getVersionNo()
                : "empty";
        String rightLabel = rightVersion != null && rightVersion.getVersionNo() != null
                ? "v" + rightVersion.getVersionNo()
                : "unknown";

        StringBuilder builder = new StringBuilder();
        builder.append("--- ").append(leftLabel).append('\n');
        builder.append("+++ ").append(rightLabel).append('\n');
        builder.append("@@ JSON Snapshot @@").append('\n');
        for (String line : diffLines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private int[][] buildLcsMatrix(List<String> leftLines, List<String> rightLines) {
        int leftSize = leftLines.size();
        int rightSize = rightLines.size();
        int[][] matrix = new int[leftSize + 1][rightSize + 1];
        for (int i = 1; i <= leftSize; i++) {
            for (int j = 1; j <= rightSize; j++) {
                if (Objects.equals(leftLines.get(i - 1), rightLines.get(j - 1))) {
                    matrix[i][j] = matrix[i - 1][j - 1] + 1;
                } else {
                    matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1]);
                }
            }
        }
        return matrix;
    }

    private LinkedList<String> buildDiffLines(List<String> leftLines,
                                              List<String> rightLines,
                                              int[][] lcsMatrix) {
        int i = leftLines.size();
        int j = rightLines.size();
        LinkedList<String> diffLines = new LinkedList<>();
        while (i > 0 && j > 0) {
            String leftLine = leftLines.get(i - 1);
            String rightLine = rightLines.get(j - 1);
            if (Objects.equals(leftLine, rightLine)) {
                diffLines.addFirst(" " + leftLine);
                i--;
                j--;
                continue;
            }
            if (lcsMatrix[i - 1][j] >= lcsMatrix[i][j - 1]) {
                diffLines.addFirst("-" + leftLine);
                i--;
            } else {
                diffLines.addFirst("+" + rightLine);
                j--;
            }
        }
        while (i > 0) {
            diffLines.addFirst("-" + leftLines.get(i - 1));
            i--;
        }
        while (j > 0) {
            diffLines.addFirst("+" + rightLines.get(j - 1));
            j--;
        }
        return diffLines;
    }

    private String toPrettyJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }

    private Long resolveLastSuccessfulPublishedVersionId(Long workflowId) {
        WorkflowPublishRecord latestSuccess = workflowPublishRecordMapper.selectOne(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .eq(WorkflowPublishRecord::getStatus, "success")
                        .isNotNull(WorkflowPublishRecord::getVersionId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt)
                        .orderByDesc(WorkflowPublishRecord::getId)
                        .last("limit 1"));
        return latestSuccess != null ? latestSuccess.getVersionId() : null;
    }

    private WorkflowVersion requireVersion(Long workflowId, Long versionId) {
        WorkflowVersion version = workflowVersionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getWorkflowId(), workflowId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND,
                    "版本不存在或不属于当前工作流: versionId=" + versionId);
        }
        return version;
    }

    private SnapshotNormalized normalizeSnapshot(WorkflowVersion version) {
        if (version == null) {
            return SnapshotNormalized.empty();
        }
        String json = version.getStructureSnapshot();
        if (!StringUtils.hasText(json)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "版本快照为空: versionId=" + version.getId());
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception ex) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "版本快照格式不合法: versionId=" + version.getId());
        }

        SnapshotNormalized normalized = new SnapshotNormalized();
        normalized.setRoot(node);

        int schemaVersion = node.path("schemaVersion").asInt(1);
        normalized.setSchemaVersion(schemaVersion);
        if (schemaVersion >= 2 && node.has("workflow") && node.has("tasks")) {
            normalized.setRollbackSupported(true);
            normalized.setWorkflow(node.path("workflow"));
            normalized.setSchedule(node.path("schedule"));
            normalized.setTasks(toTaskMap(node.path("tasks")));
            normalized.setTaskSnapshots(toTaskSnapshotList(node.path("tasks")));
            normalized.setEdges(toEdgeSet(node.path("edges")));
            return normalized;
        }

        // legacy v1 snapshot fallback (compare only)
        ObjectNode workflow = objectMapper.createObjectNode();
        copyIfExists(node, workflow, "workflowId");
        copyIfExists(node, workflow, "workflowName");
        copyIfExists(node, workflow, "definitionJson");
        copyIfExists(node, workflow, "taskGroupName");
        copyIfExists(node, workflow, "updatedBy");
        normalized.setWorkflow(workflow);
        normalized.setSchedule(objectMapper.createObjectNode());

        JsonNode tasksNode = node.path("tasks");
        normalized.setTasks(toTaskMap(tasksNode));
        normalized.setTaskSnapshots(toTaskSnapshotList(tasksNode));
        normalized.setEdges(Collections.emptySet());
        normalized.setRollbackSupported(false);
        return normalized;
    }

    private List<TaskSnapshot> toTaskSnapshotList(JsonNode tasksNode) {
        if (tasksNode == null || tasksNode.isNull() || !tasksNode.isArray()) {
            return Collections.emptyList();
        }
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || taskNode.isNull()) {
                continue;
            }
            Long taskId = firstLong(taskNode, "taskId", "id");
            if (taskId == null) {
                continue;
            }
            TaskSnapshot snapshot = new TaskSnapshot();
            snapshot.setTaskId(taskId);
            snapshot.setNode(taskNode);
            snapshot.setInputTableIds(toLongList(taskNode.path("inputTableIds")));
            snapshot.setOutputTableIds(toLongList(taskNode.path("outputTableIds")));
            snapshots.add(snapshot);
        }
        snapshots.sort(Comparator.comparing(TaskSnapshot::getTaskId));
        return snapshots;
    }

    private Map<String, JsonNode> toTaskMap(JsonNode tasksNode) {
        if (tasksNode == null || tasksNode.isNull() || !tasksNode.isArray()) {
            return Collections.emptyMap();
        }
        Map<String, JsonNode> map = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || taskNode.isNull()) {
                continue;
            }
            String key = firstText(taskNode, "taskId", "taskCode", "id", "code");
            if (!StringUtils.hasText(key)) {
                key = String.valueOf(index);
            }
            map.putIfAbsent(key, taskNode);
            index++;
        }
        return map;
    }

    private Set<String> toEdgeSet(JsonNode edgesNode) {
        if (edgesNode == null || edgesNode.isNull() || !edgesNode.isArray()) {
            return Collections.emptySet();
        }
        Set<String> edges = new LinkedHashSet<>();
        for (JsonNode edge : edgesNode) {
            if (edge == null || edge.isNull()) {
                continue;
            }
            String upstream = firstText(edge, "upstreamTaskId", "upstreamTaskCode");
            String downstream = firstText(edge, "downstreamTaskId", "downstreamTaskCode");
            if (!StringUtils.hasText(upstream) || !StringUtils.hasText(downstream)) {
                continue;
            }
            edges.add(upstream + "->" + downstream);
        }
        return edges;
    }

    private Set<String> fieldNames(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private String describeTask(JsonNode taskNode, String key) {
        if (taskNode == null || taskNode.isNull()) {
            return key;
        }
        String name = firstText(taskNode, "taskName", "name");
        if (StringUtils.hasText(name)) {
            return key + ":" + name;
        }
        return key;
    }

    private void copyIfExists(JsonNode source, ObjectNode target, String field) {
        if (source == null || target == null || !source.has(field)) {
            return;
        }
        target.set(field, source.get(field));
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText(null);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private Long firstLong(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            Long converted = asLong(value);
            if (converted != null) {
                return converted;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String field, String defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asText(defaultValue);
    }

    private Long readLong(JsonNode node, String field, Long defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        Long converted = asLong(value);
        return converted != null ? converted : defaultValue;
    }

    private Integer readInteger(JsonNode node, String field, Integer defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private Boolean readBoolean(JsonNode node, String field, Boolean defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private LocalDateTime readDateTime(JsonNode node, String field, LocalDateTime defaultValue) {
        String text = readText(node, field, null);
        if (!StringUtils.hasText(text)) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // fallback
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException ignored) {
            return defaultValue;
        }
    }

    private Long asLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isLong() || value.isInt()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Long> toLongList(JsonNode arrayNode) {
        if (arrayNode == null || arrayNode.isNull() || !arrayNode.isArray()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            Long value = asLong(node);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private String toText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return node.toString();
    }

    private String resolveOperator(String operator) {
        if (StringUtils.hasText(operator)) {
            return operator;
        }
        return "portal-ui";
    }

    private IllegalArgumentException badRequest(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    @Data
    private static class SnapshotNormalized {

        private int schemaVersion;

        private JsonNode root;

        private JsonNode workflow;

        private JsonNode schedule;

        private Map<String, JsonNode> tasks = Collections.emptyMap();

        private List<TaskSnapshot> taskSnapshots = Collections.emptyList();

        private Set<String> edges = Collections.emptySet();

        private boolean rollbackSupported;

        static SnapshotNormalized empty() {
            SnapshotNormalized snapshot = new SnapshotNormalized();
            snapshot.setSchemaVersion(0);
            snapshot.setWorkflow(null);
            snapshot.setSchedule(null);
            snapshot.setTasks(Collections.emptyMap());
            snapshot.setTaskSnapshots(Collections.emptyList());
            snapshot.setEdges(Collections.emptySet());
            snapshot.setRollbackSupported(false);
            return snapshot;
        }
    }

    @Data
    private static class TaskSnapshot {

        private Long taskId;

        private JsonNode node;

        private List<Long> inputTableIds = new ArrayList<>();

        private List<Long> outputTableIds = new ArrayList<>();
    }
}
