package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.*;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client wrapper around the DolphinScheduler OpenAPI.
 *
 * <p>
 * Encapsulates request/response mapping and workflow helper utilities so the
 * rest of the codebase can manage tasks without depending on an additional
 * Python layer.
 * </p>
 */
@Slf4j
@Service
public class DolphinSchedulerService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final DolphinConfigService dolphinConfigService;
    private final ObjectMapper objectMapper;
    private final DolphinOpenApiClient openApiClient;
    private final AtomicLong taskCodeSequence = new AtomicLong(System.currentTimeMillis());

    // Cache for project code to avoid repeated API calls
    private volatile Long cachedProjectCode;

    public DolphinSchedulerService(DolphinConfigService dolphinConfigService,
            ObjectMapper objectMapper,
            DolphinOpenApiClient openApiClient) {
        this.dolphinConfigService = dolphinConfigService;
        this.objectMapper = objectMapper;
        this.openApiClient = openApiClient;
    }

    private DolphinConfig getConfig() {
        DolphinConfig config = dolphinConfigService.getActiveConfig();
        if (config == null) {
            throw new IllegalStateException("DolphinScheduler configuration is missing");
        }
        return config;
    }

    /**
     * Query project code from DolphinScheduler by project name.
     * Results are cached to avoid repeated API calls.
     */
    public Long getProjectCode() {
        return getProjectCode(false);
    }

    /**
     * Query project code with option to force refresh the cache.
     */
    public Long getProjectCode(boolean forceRefresh) {
        if (!forceRefresh && cachedProjectCode != null) {
            return cachedProjectCode;
        }

        synchronized (this) {
            if (!forceRefresh && cachedProjectCode != null) {
                return cachedProjectCode;
            }

            String projectName = getConfig().getProjectName();
            try {
                DolphinProject project = openApiClient.getProject(projectName);
                if (project != null) {
                    cachedProjectCode = project.getCode();
                    log.info("Queried project code for {}: {}", projectName, cachedProjectCode);
                    return cachedProjectCode;
                } else {
                    log.info("Project {} not found. Attempting to create it...", projectName);
                    try {
                        Long newCode = openApiClient.createProject(projectName,
                                "Auto-created by OpenDataWorks");
                        if (newCode != null && newCode > 0) {
                            cachedProjectCode = newCode;
                            log.info("Created project {}: {}", projectName, cachedProjectCode);
                            return cachedProjectCode;
                        }
                    } catch (Exception ex) {
                        log.error("Failed to auto-create project {}", projectName, ex);
                    }

                    log.warn("Project {} could not be found or created", projectName);
                    return null;
                }
            } catch (Exception e) {
                log.warn("Failed to query project code for {}: {}", projectName, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Clear the cached project code. Use this when DolphinScheduler is reset.
     */
    public void clearProjectCodeCache() {
        cachedProjectCode = null;
        log.info("Cleared project code cache");
    }

    /**
     * Synchronise tasks, relations and locations with DolphinScheduler via OpenAPI.
     * Returns the actual workflow code (may differ from input if workflowCode was
     * 0).
     */
    public long syncWorkflow(long workflowCode,
            String workflowName,
            List<Map<String, Object>> tasks,
            List<TaskRelationPayload> relations,
            List<TaskLocationPayload> locations,
            String globalParams) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot sync workflow: Project not found");
        }

        try {
            String taskJson = objectMapper.writeValueAsString(tasks);
            String relationJson = objectMapper
                    .writeValueAsString(relations != null ? relations : Collections.emptyList());
            String locationJson = objectMapper
                    .writeValueAsString(locations != null ? locations : Collections.emptyList());

            log.info("Syncing workflow '{}' to project {}", workflowName, projectCode);

            DolphinConfig config = getConfig();
            return openApiClient.createOrUpdateProcessDefinition(
                    projectCode,
                    workflowName,
                    "", // description
                    config.getTenantCode(),
                    config.getExecutionType(),
                    relationJson,
                    taskJson,
                    locationJson,
                    globalParams,
                    workflowCode > 0 ? workflowCode : null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow data", e);
        }
    }

    /**
     * Update workflow release state (ONLINE/OFFLINE).
     */
    public void setWorkflowReleaseState(long workflowCode, String releaseState) {
        Long projectCode = getProjectCode();
        if (projectCode == null)
            return;

        openApiClient.releaseProcessDefinition(projectCode, workflowCode, releaseState);
        log.info("Updated Dolphin workflow {} release state to {}", workflowCode, releaseState);
    }

    /**
     * Check if workflow definition exists in DolphinScheduler.
     */
    public boolean checkWorkflowExists(long workflowCode) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            return false;
        }

        try {
            // Try to query the workflow definition
            // If it doesn't exist, the API will throw an exception
            openApiClient.getProcessDefinition(projectCode, workflowCode);
            return true;
        } catch (Exception e) {
            log.debug("Workflow {} does not exist: {}", workflowCode, e.getMessage());
            return false;
        }
    }

    /**
     * Start workflow instance via DolphinScheduler OpenAPI.
     */
    public String startProcessInstance(Long workflowCode, String projectName, String workflowName) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot start workflow: Project not found");
        }

        DolphinConfig config = getConfig();
        Long instanceId = openApiClient.startProcessInstance(
                projectCode,
                workflowCode,
                "", // scheduleTime
                "CONTINUE", // failureStrategy
                "NONE", // warningType
                null, // warningGroupId
                "START_PROCESS",
                config.getWorkerGroup(),
                config.getTenantCode());

        String executionId = instanceId != null ? String.valueOf(instanceId) : "exec-" + System.currentTimeMillis();
        log.info("Started workflow instance for definition {} -> {}", workflowCode, executionId);
        return executionId;
    }

    /**
     * Delete workflow definition via DolphinScheduler OpenAPI.
     */
    public void deleteWorkflow(Long workflowCode) {
        if (workflowCode == null)
            return;

        Long projectCode = getProjectCode();
        if (projectCode == null)
            return;

        // Ensure offline before delete
        try {
            setWorkflowReleaseState(workflowCode, "OFFLINE");
        } catch (Exception ignored) {
        }

        openApiClient.deleteProcessDefinition(projectCode, workflowCode);
        log.info("Deleted DolphinScheduler workflow {}", workflowCode);
    }

    /**
     * Get workflow instance status via DolphinScheduler OpenAPI.
     */
    public JsonNode getWorkflowInstanceStatus(Long workflowCode, String instanceId) {
        // Implementation note: converting DTO back to JsonNode to maintain
        // compatibility
        // with existing frontend/controller logic which expects raw JsonNode
        if (workflowCode == null || instanceId == null)
            return null;

        Long projectCode = getProjectCode();
        if (projectCode == null)
            return null;

        try {
            // Note: instanceId in argument is string, but DS uses long ID.
            // If instanceId comes from our startProcessInstance mock return, we can't query
            // it.
            // Assuming instanceId is a valid numeric string here.
            long id = Long.parseLong(instanceId);
            DolphinProcessInstance instance = openApiClient.getProcessInstance(projectCode, id);
            return objectMapper.valueToTree(instance);
        } catch (NumberFormatException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return null;
        }
    }

    /**
     * List workflow instances via DolphinScheduler OpenAPI.
     */
    public List<WorkflowInstanceSummary> listWorkflowInstances(Long workflowCode, int limit) {
        if (workflowCode == null || workflowCode <= 0) {
            return Collections.emptyList();
        }
        Long projectCode = getProjectCode();
        if (projectCode == null)
            return Collections.emptyList();

        int pageSize = Math.min(Math.max(limit, 1), 100);

        DolphinPageData<DolphinProcessInstance> page = openApiClient.listProcessInstances(
                projectCode, 1, pageSize, workflowCode);

        if (page == null || page.getTotalList() == null) {
            return Collections.emptyList();
        }

        List<WorkflowInstanceSummary> result = new ArrayList<>();
        for (DolphinProcessInstance instance : page.getTotalList()) {
            result.add(WorkflowInstanceSummary.builder()
                    .instanceId(instance.getId())
                    .state(instance.getState())
                    .commandType(instance.getCommandType())
                    .startTime(instance.getStartTime())
                    .endTime(instance.getEndTime())
                    .durationMs(parseDuration(instance.getDuration()))
                    .build());
        }
        return result;
    }

    /**
     * Generate DolphinScheduler Web UI URL for workflow definition.
     */
    public String getWorkflowDefinitionUrl(Long workflowCode) {
        if (workflowCode == null)
            return null;

        String baseUrl = getWebuiBaseUrl();
        if (baseUrl == null)
            return null;

        Long projectCode = getProjectCode();
        if (projectCode == null)
            return null;

        return String.format("%s/ui/projects/%d/workflow/definitions/%d",
                baseUrl, projectCode, workflowCode);
    }

    /**
     * Generate DolphinScheduler Web UI URL for task instances.
     */
    public String getTaskDefinitionUrl(Long taskCode) {
        if (taskCode == null)
            return null;

        String baseUrl = getWebuiBaseUrl();
        if (baseUrl == null)
            return null;

        Long projectCode = getProjectCode();
        if (projectCode == null)
            return null;

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(String.format("%s/ui/projects/%d/task/instances", baseUrl, projectCode))
                .queryParam("taskCode", taskCode);
        String projectName = getConfig().getProjectName();
        if (StringUtils.hasText(projectName)) {
            builder.queryParam("projectName", projectName);
        }
        return builder.build(true).toUriString();
    }

    /**
     * Return the configured DolphinScheduler Web UI base URL without trailing
     * slashes.
     */
    public String getWebuiBaseUrl() {
        String url = getConfig().getUrl();
        if (!StringUtils.hasText(url)) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    /**
     * Generate the next DolphinScheduler task code locally.
     */
    public long nextTaskCode() {
        return taskCodeSequence.incrementAndGet();
    }

    /**
     * Initialise internal sequence to avoid collisions with pre-existing codes.
     */
    public void initialiseSequence(long candidate) {
        taskCodeSequence.updateAndGet(current -> Math.max(current, candidate));
    }

    public void alignSequenceWithExistingTasks(List<Long> existingCodes) {
        if (existingCodes == null || existingCodes.isEmpty()) {
            return;
        }
        existingCodes.stream()
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .ifPresent(this::initialiseSequence);
    }

    /**
     * Build task definition payload for DolphinScheduler SHELL task.
     */
    public Map<String, Object> buildTaskDefinition(long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds) {
        return buildTaskDefinition(taskCode, taskVersion, taskName, description, rawScript,
                taskPriority, retryTimes, retryInterval, timeoutSeconds, "SHELL", null, null, null, null);
    }

    /**
     * Build task definition payload for DolphinScheduler with flexible task type.
     */
    public Map<String, Object> buildTaskDefinition(long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinition(taskCode, taskVersion, taskName, description, rawScript,
                taskPriority, retryTimes, retryInterval, timeoutSeconds, nodeType,
                datasourceId, datasourceType, null, null, null, null, taskGroupId, taskGroupPriority);
    }

    /**
     * Build task definition payload for DolphinScheduler with DataX support.
     */
    public Map<String, Object> buildTaskDefinition(long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Long targetDatasourceId,
            String sourceTable,
            String targetTable,
            String customJson,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", taskCode);
        payload.put("name", taskName);
        payload.put("version", taskVersion);
        payload.put("description", description == null ? "" : description);
        payload.put("delayTime", "0");
        payload.put("failRetryInterval", String.valueOf(Math.max(retryInterval, 1)));
        payload.put("failRetryTimes", String.valueOf(Math.max(retryTimes, 0)));
        payload.put("flag", "YES");
        payload.put("taskPriority", taskPriority);
        DolphinConfig config = getConfig();
        payload.put("workerGroup", config.getWorkerGroup());
        payload.put("environmentCode", -1);
        payload.put("taskType", nodeType == null ? "SHELL" : nodeType);
        payload.put("timeout", timeoutSeconds);
        payload.put("timeoutFlag", timeoutSeconds > 0 ? "OPEN" : "CLOSE");
        payload.put("timeoutNotifyStrategy", "FAILED");

        // Added missing fields based on user payload
        payload.put("cpuQuota", -1);
        payload.put("memoryMax", -1);
        payload.put("taskExecuteType", "BATCH");
        payload.put("isCache", "NO");
        payload.put("taskGroupId", taskGroupId == null ? 0 : taskGroupId);
        payload.put("taskGroupPriority", taskGroupPriority == null ? 0 : taskGroupPriority);

        try {
            if ("SQL".equalsIgnoreCase(nodeType)) {
                payload.put("taskParams", TaskParams.sql(rawScript, datasourceId, datasourceType));
            } else if ("DATAX".equalsIgnoreCase(nodeType)) {
                payload.put("taskParams", TaskParams.datax(datasourceId, targetDatasourceId,
                        sourceTable, targetTable, customJson));
            } else {
                payload.put("taskParams", TaskParams.shell(rawScript));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to construct task parameters", e);
        }
        return payload;
    }

    public TaskRelationPayload buildRelation(long upstreamCode, int upstreamVersion,
            long downstreamCode, int downstreamVersion) {
        TaskRelationPayload relation = new TaskRelationPayload();
        relation.setName("");
        relation.setPreTaskCode(upstreamCode);
        relation.setPreTaskVersion(upstreamVersion);
        relation.setPostTaskCode(downstreamCode);
        relation.setPostTaskVersion(downstreamVersion);
        relation.setConditionType("NONE");
        relation.setConditionParams("{}");
        return relation;
    }

    public TaskLocationPayload buildLocation(long taskCode, int index, int lane) {
        TaskLocationPayload location = new TaskLocationPayload();
        location.setTaskCode(taskCode);
        location.setX(220 + index * 280);
        location.setY(140 + lane * 140);
        return location;
    }

    public String buildShellScript(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "#!/bin/bash\n\necho \"No SQL provided\"";
        }
        String sanitised = sql.replace("\r\n", "\n");
        return "#!/bin/bash\nset -euo pipefail\ncat <<'SQL'\n" + sanitised + "\nSQL\n";
    }

    /**
     * Retrieve datasource options from DolphinScheduler OpenAPI.
     */
    public List<DolphinDatasourceOption> listDatasources(String type, String keyword) {
        try {
            List<DolphinDatasource> rawList = openApiClient.listDatasources(1, 100);

            List<DolphinDatasourceOption> result = new ArrayList<>();
            for (DolphinDatasource ds : rawList) {
                // Filter logic
                if (StringUtils.hasText(type) && !type.equalsIgnoreCase(ds.getType())) {
                    continue;
                }
                if (StringUtils.hasText(keyword) && !ds.getName().contains(keyword)) {
                    continue;
                }

                DolphinDatasourceOption option = new DolphinDatasourceOption();
                option.setId(ds.getId());
                option.setName(ds.getName());
                option.setType(ds.getType());
                option.setDbName(ds.getDatabase());
                option.setDescription(ds.getNote());
                result.add(option);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load datasources from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve task group options from DolphinScheduler OpenAPI.
     */
    public List<DolphinTaskGroupOption> listTaskGroups(String keyword) {
        try {
            DolphinPageData<DolphinTaskGroup> page = openApiClient.listTaskGroups(1, 200, keyword, null);
            if (page == null || page.getTotalList() == null) {
                return Collections.emptyList();
            }
            List<DolphinTaskGroupOption> result = new ArrayList<>();
            for (DolphinTaskGroup group : page.getTotalList()) {
                if (group == null) {
                    continue;
                }
                if (!StringUtils.hasText(group.getName())) {
                    continue;
                }
                if (StringUtils.hasText(keyword) && !group.getName().contains(keyword)) {
                    continue;
                }
                DolphinTaskGroupOption option = new DolphinTaskGroupOption();
                option.setId(group.getId());
                option.setName(group.getName());
                option.setDescription(group.getDescription());
                option.setGroupSize(group.getGroupSize());
                option.setUseSize(group.getUseSize());
                option.setStatus(group.getStatus());
                result.add(option);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load task groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private Long parseDuration(String value) {
        if (!StringUtils.hasText(value))
            return null;
        // Basic parsing, assuming simple format or ms
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Task relation payload describing dependencies.
     */
    @Getter
    public static class TaskRelationPayload {
        private String name;
        private long preTaskCode;
        private int preTaskVersion;
        private long postTaskCode;
        private int postTaskVersion;
        private String conditionType;
        private String conditionParams;

        public void setName(String name) {
            this.name = name;
        }

        public void setPreTaskCode(long preTaskCode) {
            this.preTaskCode = preTaskCode;
        }

        public void setPreTaskVersion(int preTaskVersion) {
            this.preTaskVersion = preTaskVersion;
        }

        public void setPostTaskCode(long postTaskCode) {
            this.postTaskCode = postTaskCode;
        }

        public void setPostTaskVersion(int postTaskVersion) {
            this.postTaskVersion = postTaskVersion;
        }

        public void setConditionType(String conditionType) {
            this.conditionType = conditionType;
        }

        public void setConditionParams(String conditionParams) {
            this.conditionParams = conditionParams;
        }
    }

    /**
     * Location payload for visual DAG layout.
     */
    @Getter
    public static class TaskLocationPayload {
        private long taskCode;
        private int x;
        private int y;

        public void setTaskCode(long taskCode) {
            this.taskCode = taskCode;
        }

        public void setX(int x) {
            this.x = x;
        }

        public void setY(int y) {
            this.y = y;
        }
    }

    /**
     * Parameters for shell task.
     */
    @Getter
    public static class TaskParams {
        private final List<Object> localParams = new ArrayList<>();
        private final List<Object> resourceList = new ArrayList<>();
        private final String rawScript;
        private final String type;
        private final Long datasource;
        private final String sql;
        private final String sqlType;
        private final Integer displayRows;
        private final List<String> preStatements = new ArrayList<>();
        private final List<String> postStatements = new ArrayList<>();

        // DataX-specific fields
        private final Long targetDatasource;
        private final String sourceTable;
        private final String targetTable;
        private final String customJson;

        public static TaskParams shell(String script) {
            return new TaskParams(script, null, null, null, null, null, null, null);
        }

        public static TaskParams sql(String sql, Long datasourceId, String datasourceType) {
            return new TaskParams(null, datasourceId, datasourceType, sql, null, null, null, null);
        }

        public static TaskParams datax(Long sourceDatasourceId, Long targetDatasourceId,
                String sourceTable, String targetTable,
                String customJson) {
            return new TaskParams(null, sourceDatasourceId, null, null,
                    targetDatasourceId, sourceTable, targetTable, customJson);
        }

        private TaskParams(String rawScript, Long datasourceId, String datasourceType, String sql,
                Long targetDatasourceId, String sourceTable, String targetTable, String customJson) {
            this.rawScript = rawScript;
            this.datasource = datasourceId;
            this.sql = sql;
            // SQL type: 0=QUERY, 1=NON_QUERY (as string). Default to NON_QUERY.
            this.sqlType = sql != null && sql.trim().toUpperCase().startsWith("SELECT") ? "0" : "1";
            this.displayRows = 10;
            // Don't default to MYSQL - let DolphinScheduler infer type from datasource name
            this.type = datasourceType;

            // DataX fields
            this.targetDatasource = targetDatasourceId;
            this.sourceTable = sourceTable;
            this.targetTable = targetTable;
            this.customJson = customJson;
        }
    }
}
