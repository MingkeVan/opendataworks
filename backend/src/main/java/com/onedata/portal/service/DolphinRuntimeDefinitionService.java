package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dolphin 运行态定义提取服务
 */
@Service
@RequiredArgsConstructor
public class DolphinRuntimeDefinitionService {

    private final DolphinOpenApiClient openApiClient;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final ObjectMapper objectMapper;

    public DolphinRuntimeWorkflowPage listRuntimeWorkflows(Long projectCode,
            Integer pageNum,
            Integer pageSize,
            String keyword) {
        long resolvedProjectCode = resolveProjectCode(projectCode);
        int resolvedPageNum = pageNum != null && pageNum > 0 ? pageNum : 1;
        int resolvedPageSize = pageSize != null && pageSize > 0 ? pageSize : 20;

        JsonNode page = openApiClient.listProcessDefinitions(resolvedProjectCode, resolvedPageNum, resolvedPageSize, keyword);
        if (page == null) {
            return DolphinRuntimeWorkflowPage.empty();
        }

        JsonNode listNode = page.path("totalList");
        List<DolphinRuntimeWorkflowOption> records = new ArrayList<>();
        if (listNode.isArray()) {
            for (JsonNode item : listNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                DolphinRuntimeWorkflowOption option = new DolphinRuntimeWorkflowOption();
                option.setProjectCode(resolvedProjectCode);
                option.setWorkflowCode(readLong(item, "code", "workflowCode", "processDefinitionCode"));
                option.setWorkflowName(readText(item, "name", "workflowName"));
                option.setReleaseState(readText(item, "releaseState", "publishStatus", "scheduleReleaseState"));
                records.add(option);
            }
        }

        long total = page.path("total").asLong(records.size());
        if (total <= 0 && page.path("totalList").isArray()) {
            total = page.path("totalList").size();
        }

        DolphinRuntimeWorkflowPage result = new DolphinRuntimeWorkflowPage();
        result.setTotal(total);
        result.setRecords(records);
        return result;
    }

    public RuntimeWorkflowDefinition loadRuntimeDefinition(Long projectCode, Long workflowCode) {
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalArgumentException("workflowCode 不能为空");
        }
        long resolvedProjectCode = resolveProjectCode(projectCode);
        JsonNode raw = openApiClient.getProcessDefinition(resolvedProjectCode, workflowCode);
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            throw new IllegalStateException("未找到 Dolphin 工作流定义");
        }

        JsonNode definition = unwrapDefinition(raw);

        RuntimeWorkflowDefinition result = new RuntimeWorkflowDefinition();
        result.setProjectCode(resolvedProjectCode);
        result.setWorkflowCode(workflowCode);
        result.setWorkflowName(readText(definition, "name", "workflowName"));
        result.setDescription(readText(definition, "description", "desc"));
        result.setReleaseState(readText(definition, "releaseState", "publishStatus", "scheduleReleaseState"));
        result.setGlobalParams(normalizeJsonField(definition.get("globalParams")));
        result.setSchedule(extractSchedule(definition, workflowCode));

        List<RuntimeTaskDefinition> tasks = parseTaskDefinitions(definition);
        List<RuntimeTaskEdge> explicitEdges = parseTaskEdges(definition);

        JsonNode tasksNode = openApiClient.getProcessDefinitionTasks(resolvedProjectCode, workflowCode);
        if (tasks.isEmpty()) {
            tasks = parseTaskDefinitionsFromNode(tasksNode);
        }
        if (explicitEdges.isEmpty()) {
            explicitEdges = parseTaskEdgesFromNode(tasksNode);
        }

        if (tasks.isEmpty()) {
            JsonNode taskDefinitionList = openApiClient.queryTaskDefinitionList(resolvedProjectCode, workflowCode);
            tasks = parseTaskDefinitionsFromNode(taskDefinitionList);
        }

        result.setTasks(tasks);
        result.setExplicitEdges(explicitEdges);
        result.setRawDefinitionJson(toJson(raw));
        return result;
    }

    private RuntimeWorkflowSchedule extractSchedule(JsonNode definition, Long workflowCode) {
        JsonNode scheduleNode = readNode(definition, "schedule");
        RuntimeWorkflowSchedule schedule = parseScheduleNode(scheduleNode);

        if (schedule == null || schedule.getScheduleId() == null || schedule.getScheduleId() <= 0) {
            DolphinSchedule dsSchedule = dolphinSchedulerService.getWorkflowSchedule(workflowCode);
            if (dsSchedule != null) {
                schedule = new RuntimeWorkflowSchedule();
                schedule.setScheduleId(dsSchedule.getId());
                schedule.setReleaseState(dsSchedule.getReleaseState());
                schedule.setCrontab(dsSchedule.getCrontab());
                schedule.setTimezoneId(dsSchedule.getTimezoneId());
                schedule.setStartTime(dsSchedule.getStartTime());
                schedule.setEndTime(dsSchedule.getEndTime());
                schedule.setFailureStrategy(dsSchedule.getFailureStrategy());
                schedule.setWarningType(dsSchedule.getWarningType());
                schedule.setWarningGroupId(dsSchedule.getWarningGroupId());
                schedule.setProcessInstancePriority(dsSchedule.getProcessInstancePriority());
                schedule.setWorkerGroup(dsSchedule.getWorkerGroup());
                schedule.setTenantCode(dsSchedule.getTenantCode());
                schedule.setEnvironmentCode(dsSchedule.getEnvironmentCode());
            }
        }

        if (schedule != null && !StringUtils.hasText(schedule.getReleaseState())) {
            schedule.setReleaseState(readText(definition, "scheduleReleaseState", "releaseState"));
        }
        return schedule;
    }

    private RuntimeWorkflowSchedule parseScheduleNode(JsonNode scheduleNode) {
        if (scheduleNode == null || scheduleNode.isNull() || scheduleNode.isMissingNode()) {
            return null;
        }
        JsonNode node = normalizeNode(scheduleNode);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(readLong(node, "id", "scheduleId"));
        schedule.setReleaseState(readText(node, "releaseState"));
        schedule.setCrontab(readText(node, "crontab", "cron"));
        schedule.setTimezoneId(readText(node, "timezoneId", "timezone"));
        schedule.setStartTime(readText(node, "startTime"));
        schedule.setEndTime(readText(node, "endTime"));
        schedule.setFailureStrategy(readText(node, "failureStrategy"));
        schedule.setWarningType(readText(node, "warningType"));
        schedule.setWarningGroupId(readLong(node, "warningGroupId"));
        schedule.setProcessInstancePriority(readText(node, "processInstancePriority"));
        schedule.setWorkerGroup(readText(node, "workerGroup"));
        schedule.setTenantCode(readText(node, "tenantCode"));
        schedule.setEnvironmentCode(readLong(node, "environmentCode"));
        return schedule;
    }

    private List<RuntimeTaskDefinition> parseTaskDefinitions(JsonNode definition) {
        JsonNode taskNode = firstPresentNode(definition,
                "taskDefinitionJson",
                "taskDefinitionList",
                "taskList",
                "tasks");
        return parseTaskDefinitionsFromNode(taskNode);
    }

    private List<RuntimeTaskDefinition> parseTaskDefinitionsFromNode(JsonNode taskNode) {
        JsonNode normalized = normalizeNode(taskNode);
        if (normalized != null && normalized.isObject()) {
            JsonNode inner = firstPresentNode(normalized,
                    "taskDefinitionJson",
                    "taskDefinitionList",
                    "taskList",
                    "tasks");
            if (inner != null && !inner.isMissingNode() && !inner.isNull()) {
                normalized = normalizeNode(inner);
            }
        }
        if (normalized == null || !normalized.isArray()) {
            return Collections.emptyList();
        }

        List<RuntimeTaskDefinition> tasks = new ArrayList<>();
        for (JsonNode item : normalized) {
            if (item == null || item.isNull()) {
                continue;
            }
            RuntimeTaskDefinition task = new RuntimeTaskDefinition();
            task.setTaskCode(readLong(item, "code", "taskCode"));
            task.setTaskVersion(readInt(item, "version", "taskVersion"));
            task.setTaskName(readText(item, "name", "taskName"));
            task.setDescription(readText(item, "description", "taskDesc"));
            task.setNodeType(readText(item, "taskType", "nodeType", "type"));
            task.setTimeoutSeconds(readInt(item, "timeout", "timeoutSeconds"));
            task.setRetryTimes(readInt(item, "failRetryTimes", "retryTimes"));
            task.setRetryInterval(readInt(item, "failRetryInterval", "retryInterval"));
            task.setTaskPriority(readText(item, "taskPriority", "priority"));
            task.setTaskGroupId(readInt(item, "taskGroupId"));
            task.setTaskGroupName(readText(item, "taskGroupName"));

            JsonNode taskParamsNode = normalizeNode(item.get("taskParams"));
            if (taskParamsNode != null && !taskParamsNode.isNull()) {
                task.setSql(readText(taskParamsNode, "sql", "rawScript"));
                task.setDatasourceId(readLong(taskParamsNode, "datasource", "datasourceId"));
                task.setDatasourceType(readText(taskParamsNode, "type", "datasourceType"));
            }
            if (!StringUtils.hasText(task.getSql())) {
                task.setSql(readText(item, "sql", "rawScript"));
            }
            tasks.add(task);
        }
        return tasks;
    }

    private List<RuntimeTaskEdge> parseTaskEdges(JsonNode definition) {
        JsonNode relationNode = firstPresentNode(definition, "taskRelationJson", "taskRelationList");
        return parseTaskEdgesFromNode(relationNode);
    }

    private List<RuntimeTaskEdge> parseTaskEdgesFromNode(JsonNode relationNode) {
        JsonNode normalized = normalizeNode(relationNode);
        if (normalized != null && normalized.isObject()) {
            JsonNode inner = firstPresentNode(normalized,
                    "taskRelationJson",
                    "taskRelationList",
                    "edges");
            if (inner != null && !inner.isMissingNode() && !inner.isNull()) {
                normalized = normalizeNode(inner);
            }
        }
        if (normalized == null || !normalized.isArray()) {
            return Collections.emptyList();
        }

        List<RuntimeTaskEdge> edges = new ArrayList<>();
        for (JsonNode relation : normalized) {
            if (relation == null || relation.isNull()) {
                continue;
            }
            Long preTaskCode = readLong(relation, "preTaskCode", "preTask", "upstreamTaskCode");
            Long postTaskCode = readLong(relation, "postTaskCode", "postTask", "downstreamTaskCode");
            if (postTaskCode == null || postTaskCode <= 0) {
                continue;
            }
            if (preTaskCode == null || preTaskCode <= 0) {
                continue;
            }
            edges.add(new RuntimeTaskEdge(preTaskCode, postTaskCode));
        }
        return edges;
    }

    private JsonNode unwrapDefinition(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return raw;
        }
        JsonNode processDefinition = readNode(raw, "processDefinition");
        if (processDefinition != null && processDefinition.isObject()) {
            return processDefinition;
        }
        JsonNode processDefinitionJson = readNode(raw, "processDefinitionJson");
        JsonNode normalized = normalizeNode(processDefinitionJson);
        if (normalized != null && normalized.isObject()) {
            return normalized;
        }
        return raw;
    }

    private JsonNode firstPresentNode(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = readNode(node, fieldName);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                return objectMapper.readTree(text);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        return node;
    }

    private String normalizeJsonField(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText();
            return StringUtils.hasText(value) ? value : null;
        }
        return toJson(node);
    }

    private JsonNode readNode(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String... fieldNames) {
        JsonNode field = readNode(node, fieldNames);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return null;
        }
        if (field.isTextual()) {
            String text = field.asText();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        String text = field.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Long readLong(JsonNode node, String... fieldNames) {
        JsonNode field = readNode(node, fieldNames);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return null;
        }
        if (field.isIntegralNumber()) {
            return field.asLong();
        }
        if (field.isTextual()) {
            String text = field.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fieldNames) {
        Long value = readLong(node, fieldNames);
        if (value == null) {
            return null;
        }
        return value.intValue();
    }

    private long resolveProjectCode(Long projectCode) {
        if (projectCode != null && projectCode > 0) {
            return projectCode;
        }
        Long currentProjectCode = dolphinSchedulerService.getProjectCode();
        if (currentProjectCode == null || currentProjectCode <= 0) {
            throw new IllegalStateException("无法获取 Dolphin projectCode");
        }
        return currentProjectCode;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    @Data
    public static class DolphinRuntimeWorkflowPage {
        private Long total = 0L;
        private List<DolphinRuntimeWorkflowOption> records = new ArrayList<>();

        public static DolphinRuntimeWorkflowPage empty() {
            return new DolphinRuntimeWorkflowPage();
        }
    }
}
