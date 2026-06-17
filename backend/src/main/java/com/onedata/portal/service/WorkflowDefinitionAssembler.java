package com.onedata.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流定义 JSON 组装与运行态绑定处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionAssembler {

    private final ObjectMapper objectMapper;
    private final DolphinSchedulerService dolphinSchedulerService;

    public String refreshRuntimeBindings(String definitionJson, Long dolphinConfigId, Long projectCode) {
        if (!StringUtils.hasText(definitionJson)) {
            return definitionJson;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (!(root instanceof ObjectNode)) {
                return definitionJson;
            }
            ObjectNode rootObject = (ObjectNode) root;
            resetDefinitionRuntimeBinding(rootObject, projectCode);
            enrichMetadataFromCatalog(rootObject, dolphinConfigId);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            log.warn("Failed to refresh workflow definition metadata for Dolphin config {}: {}",
                    dolphinConfigId, ex.getMessage());
            return definitionJson;
        }
    }

    public void enrichMetadataFromCatalog(ObjectNode rootNode, Long dolphinConfigId) {
        if (rootNode == null || rootNode.isNull() || rootNode.isMissingNode()) {
            return;
        }
        JsonNode taskListNode = rootNode.get("taskDefinitionList");
        if (!(taskListNode instanceof ArrayNode)) {
            return;
        }

        String workflowTaskGroupName = normalizeText(readText(
                firstPresent(rootNode, "processDefinition", "workflowDefinition"),
                "taskGroupName"));
        boolean needDatasourceResolve = false;
        boolean needTaskGroupResolve = false;
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObjectNode(taskObject, "taskParams");
            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = normalizeText(readText(taskParams, "datasourceName"));
            if (StringUtils.hasText(datasourceName) || (datasourceId != null && datasourceId > 0)) {
                needDatasourceResolve = true;
            }

            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = normalizeText(readText(taskObject, "taskGroupName"));
            if (!StringUtils.hasText(taskGroupName)) {
                taskGroupName = workflowTaskGroupName;
                if (StringUtils.hasText(taskGroupName)) {
                    taskObject.put("taskGroupName", taskGroupName);
                }
            }
            if ((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName)) {
                needTaskGroupResolve = true;
            }
        }
        if (!needDatasourceResolve && !needTaskGroupResolve) {
            return;
        }

        DatasourceCatalog datasourceCatalog = needDatasourceResolve
                ? loadDatasourceCatalog(dolphinConfigId)
                : DatasourceCatalog.empty();
        Map<String, DolphinTaskGroupOption> taskGroupByName = needTaskGroupResolve
                ? loadTaskGroupCatalogByName(dolphinConfigId)
                : Collections.emptyMap();

        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObjectNode(taskObject, "taskParams");

            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = normalizeText(readText(taskParams, "datasourceName"));
            DolphinDatasourceOption datasourceOption = resolveDatasourceOption(
                    datasourceCatalog, datasourceId, datasourceName);
            if (datasourceOption != null && datasourceOption.getId() != null && datasourceOption.getId() > 0) {
                taskParams.put("datasourceId", datasourceOption.getId());
                taskParams.put("datasource", datasourceOption.getId());
                String datasourceType = normalizeText(datasourceOption.getType());
                if (StringUtils.hasText(datasourceType)) {
                    taskParams.put("datasourceType", datasourceType);
                    taskParams.put("type", datasourceType);
                }
            }

            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = normalizeText(readText(taskObject, "taskGroupName"));
            if (!StringUtils.hasText(taskGroupName)) {
                taskGroupName = workflowTaskGroupName;
            }
            if ((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName)) {
                DolphinTaskGroupOption taskGroupOption = taskGroupByName.get(taskGroupName);
                if (taskGroupOption != null && taskGroupOption.getId() != null && taskGroupOption.getId() > 0) {
                    taskObject.put("taskGroupId", taskGroupOption.getId());
                }
            }
        }
    }

    private void resetDefinitionRuntimeBinding(ObjectNode rootObject, Long projectCode) {
        if (rootObject == null) {
            return;
        }
        resetWorkflowDefinitionNode(firstPresent(rootObject, "processDefinition"), projectCode);
        resetWorkflowDefinitionNode(firstPresent(rootObject, "workflowDefinition"), projectCode);
        resetWorkflowDefinitionNode(firstPresent(rootObject, "workflow"), projectCode);

        JsonNode metaNode = rootObject.get("xPlatformWorkflowMeta");
        if (metaNode instanceof ObjectNode) {
            ObjectNode metaObject = (ObjectNode) metaNode;
            metaObject.remove("workflowCode");
            if (projectCode != null && projectCode > 0) {
                metaObject.put("projectCode", projectCode);
            } else {
                metaObject.remove("projectCode");
            }
        }

        resetScheduleRuntimeBinding(rootObject.get("schedule"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "processDefinition"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "workflowDefinition"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "workflow"));
        resetTaskGroupRuntimeBinding(rootObject.get("taskDefinitionList"));
    }

    private void resetWorkflowDefinitionNode(JsonNode node, Long projectCode) {
        if (!(node instanceof ObjectNode)) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        object.remove("code");
        object.remove("workflowCode");
        object.remove("processDefinitionCode");
        if (projectCode != null && projectCode > 0) {
            object.put("projectCode", projectCode);
        } else {
            object.remove("projectCode");
        }
    }

    private void resetNestedScheduleRuntimeBinding(JsonNode definitionNode) {
        if (!(definitionNode instanceof ObjectNode)) {
            return;
        }
        resetScheduleRuntimeBinding(definitionNode.get("schedule"));
    }

    private void resetScheduleRuntimeBinding(JsonNode scheduleNode) {
        if (!(scheduleNode instanceof ObjectNode)) {
            return;
        }
        ObjectNode scheduleObject = (ObjectNode) scheduleNode;
        scheduleObject.remove("id");
        scheduleObject.remove("scheduleId");
        scheduleObject.remove("dolphinScheduleId");
        scheduleObject.put("scheduleState", "OFFLINE");
        scheduleObject.put("releaseState", "OFFLINE");
    }

    private void resetTaskGroupRuntimeBinding(JsonNode taskListNode) {
        if (!(taskListNode instanceof ArrayNode)) {
            return;
        }
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (taskNode instanceof ObjectNode) {
                ((ObjectNode) taskNode).remove("taskGroupId");
            }
        }
    }

    private DatasourceCatalog loadDatasourceCatalog(Long dolphinConfigId) {
        try {
            List<DolphinDatasourceOption> options = dolphinConfigId == null
                    ? dolphinSchedulerService.listDatasources(null, null)
                    : dolphinSchedulerService.listDatasources(null, null, dolphinConfigId);
            if (CollectionUtils.isEmpty(options)) {
                return DatasourceCatalog.empty();
            }
            Map<String, DolphinDatasourceOption> byName = new LinkedHashMap<>();
            Map<Long, DolphinDatasourceOption> byId = new LinkedHashMap<>();
            for (DolphinDatasourceOption option : options) {
                if (option == null || option.getId() == null || option.getId() <= 0) {
                    continue;
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    byName.putIfAbsent(name, option);
                }
                byId.putIfAbsent(option.getId(), option);
            }
            return new DatasourceCatalog(byName, byId);
        } catch (Exception ex) {
            log.warn("Failed to load datasource catalog while enriching workflow definition metadata: {}",
                    ex.getMessage());
            return DatasourceCatalog.empty();
        }
    }

    private DolphinDatasourceOption resolveDatasourceOption(DatasourceCatalog datasourceCatalog,
            Long datasourceId,
            String datasourceName) {
        if (datasourceCatalog == null) {
            return null;
        }
        String normalizedName = normalizeText(datasourceName);
        if (StringUtils.hasText(normalizedName)) {
            DolphinDatasourceOption option = datasourceCatalog.byName.get(normalizedName);
            if (option != null) {
                return option;
            }
        }
        if (datasourceId != null && datasourceId > 0) {
            return datasourceCatalog.byId.get(datasourceId);
        }
        return null;
    }

    private Map<String, DolphinTaskGroupOption> loadTaskGroupCatalogByName(Long dolphinConfigId) {
        try {
            List<DolphinTaskGroupOption> options = dolphinConfigId == null
                    ? dolphinSchedulerService.listTaskGroups(null)
                    : dolphinSchedulerService.listTaskGroups(null, dolphinConfigId);
            if (CollectionUtils.isEmpty(options)) {
                return Collections.emptyMap();
            }
            Map<String, DolphinTaskGroupOption> result = new LinkedHashMap<>();
            for (DolphinTaskGroupOption option : options) {
                if (option == null || option.getId() == null || option.getId() <= 0) {
                    continue;
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    result.putIfAbsent(name, option);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load task group catalog while enriching workflow definition metadata: {}",
                    ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode() || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private ObjectNode ensureObjectNode(ObjectNode root, String fieldName) {
        JsonNode existing = root.get(fieldName);
        if (existing instanceof ObjectNode) {
            return (ObjectNode) existing;
        }
        ObjectNode created = objectMapper.createObjectNode();
        root.set(fieldName, created);
        return created;
    }

    private Long readLong(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull() || node.isMissingNode() || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException e) {
                    log.trace("解析 Long 失败，跳过无效文本: {}", text, e);
                }
            }
        }
        return null;
    }

    private String readText(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull() || node.isMissingNode() || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            String text = value.isTextual() ? value.asText() : value.toString();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fieldNames) {
        Long value = readLong(node, fieldNames);
        return value == null ? null : value.intValue();
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static final class DatasourceCatalog {
        private final Map<String, DolphinDatasourceOption> byName;
        private final Map<Long, DolphinDatasourceOption> byId;

        private DatasourceCatalog(Map<String, DolphinDatasourceOption> byName,
                Map<Long, DolphinDatasourceOption> byId) {
            this.byName = byName;
            this.byId = byId;
        }

        private static DatasourceCatalog empty() {
            return new DatasourceCatalog(Collections.emptyMap(), Collections.emptyMap());
        }
    }
}
