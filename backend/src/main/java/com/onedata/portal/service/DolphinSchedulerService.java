package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.DolphinSchedulerProperties;
import com.onedata.portal.dto.DolphinDatasourceOption;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin client for the Python-based dolphinscheduler-service.
 *
 * <p>The Java backend now delegates workflow orchestration to the Python
 * service which wraps DolphinScheduler via the official SDK. This class
 * focuses on request/response mapping and retains backward-compatible
 * helper methods for building task payloads.</p>
 */
@Slf4j
@Service
public class DolphinSchedulerService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final DolphinSchedulerProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient serviceClient;
    private final AtomicLong taskCodeSequence = new AtomicLong(System.currentTimeMillis());

    // Cache for project code to avoid repeated API calls
    private volatile Long cachedProjectCode;

    public DolphinSchedulerService(DolphinSchedulerProperties properties,
                                   ObjectMapper objectMapper,
                                   WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.serviceClient = builder
            .baseUrl(properties.getServiceUrl())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * Ensure the unified workflow exists on DolphinScheduler and return its code.
     */
    public long ensureWorkflow() {
        Map<String, Object> body = new HashMap<>();
        body.put("workflowName", properties.getWorkflowName());
        body.put("projectName", properties.getProjectName());
        body.put("tenantCode", properties.getTenantCode());
        body.put("executionType", properties.getExecutionType());
        body.put("workerGroup", properties.getWorkerGroup());
        JsonNode data = postJson("/api/v1/workflows/ensure", body);
        long workflowCode = data.path("workflowCode").asLong();
        if (workflowCode <= 0L) {
            throw new IllegalStateException("Invalid workflowCode returned from dolphinscheduler-service");
        }
        if (data.path("created").asBoolean(false)) {
            log.info("Created DolphinScheduler workflow {} with code {}", properties.getWorkflowName(), workflowCode);
        } else {
            log.info("Workflow {} already exists with code {}", properties.getWorkflowName(), workflowCode);
        }
        return workflowCode;
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

            try {
                Map<String, Object> body = new HashMap<>();
                body.put("projectName", properties.getProjectName());
                JsonNode data = postJson("/api/v1/projects/query", body);
                cachedProjectCode = data.path("projectCode").asLong();
                log.info("Queried project code for {}: {}", properties.getProjectName(), cachedProjectCode);
                return cachedProjectCode;
            } catch (Exception e) {
                log.warn("Failed to query project code for {}: {}", properties.getProjectName(), e.getMessage());
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
     * Synchronise tasks, relations and locations with the dolphinscheduler-service.
     * Returns the actual workflow code (may differ from input if workflowCode was 0).
     */
    public long syncWorkflow(long workflowCode,
                             String workflowName,
                             List<Map<String, Object>> tasks,
                             List<TaskRelationPayload> relations,
                             List<TaskLocationPayload> locations) {
        Map<String, Object> body = new HashMap<>();
        body.put("workflowName", workflowName);
        body.put("projectName", properties.getProjectName());
        body.put("tenantCode", properties.getTenantCode());
        body.put("executionType", properties.getExecutionType());
        body.put("workerGroup", properties.getWorkerGroup());
        body.put("tasks", tasks);
        body.put("relations", relations == null ? Collections.emptyList() : relations);
        body.put("locations", locations == null ? Collections.emptyList() : locations);

        try {
            String debugPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            log.debug("Syncing workflow {} with payload:\n{}", workflowCode, debugPayload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize debug payload", e);
        }

        JsonNode response = postJson(String.format("/api/v1/workflows/%d/sync", workflowCode), body);
        long actualWorkflowCode = response.path("workflowCode").asLong(workflowCode);
        int taskCount = response.path("taskCount").asInt(tasks.size());
        log.info("Synchronized Dolphin workflow {}({}) with {} tasks via dolphinscheduler-service",
            workflowName, actualWorkflowCode, taskCount);
        return actualWorkflowCode;
    }

    public String getWorkflowName() {
        return properties.getWorkflowName();
    }

    /**
     * Update workflow release state (ONLINE/OFFLINE).
     */
    public void setWorkflowReleaseState(long workflowCode, String releaseState) {
        Map<String, Object> body = new HashMap<>();
        body.put("releaseState", releaseState);
        body.put("projectName", properties.getProjectName());
        body.put("workflowName", properties.getWorkflowName());
        postJson(String.format("/api/v1/workflows/%d/release", workflowCode), body);
        log.info("Updated Dolphin workflow {} release state to {}", workflowCode, releaseState);
    }

    /**
     * Start workflow instance via dolphinscheduler-service.
     */
    public String startProcessInstance(Long workflowCode) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("projectName", properties.getProjectName());
        body.put("workflowName", properties.getWorkflowName());
        JsonNode data = postJson(String.format("/api/v1/workflows/%d/start", workflowCode), body);
        String executionId = Optional.ofNullable(data.path("instanceId").asText(null))
            .orElse("exec-" + System.currentTimeMillis());
        log.info("Invoked dolphinscheduler-service start for workflow {} -> {}", workflowCode, executionId);
        return executionId;
    }

    /**
     * Delete workflow definition via dolphinscheduler-service.
     * Used for cleaning up temporary test workflows.
     */
    public void deleteWorkflow(Long workflowCode) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("projectName", properties.getProjectName());
            postJson(String.format("/api/v1/workflows/%d/delete", workflowCode), body);
            log.info("Deleted DolphinScheduler workflow {}", workflowCode);
        } catch (Exception e) {
            log.warn("Failed to delete workflow {}: {}", workflowCode, e.getMessage());
        }
    }

    /**
     * Get workflow instance status via dolphinscheduler-service.
     * Returns instance information including state, start time, end time, etc.
     */
    public JsonNode getWorkflowInstanceStatus(Long workflowCode, String instanceId) {
        if (workflowCode == null || instanceId == null) {
            throw new IllegalArgumentException("workflowCode and instanceId must not be null");
        }
        try {
            String path = String.format("/api/v1/workflows/%d/instances/%s", workflowCode, instanceId);
            Map<String, Object> body = new HashMap<>();
            body.put("projectName", properties.getProjectName());
            JsonNode data = postJson(path, body);
            log.debug("Retrieved instance status for workflow {} instance {}: {}",
                workflowCode, instanceId, data.path("state").asText());
            return data;
        } catch (Exception e) {
            log.warn("Failed to get instance status for workflow {} instance {}: {}",
                workflowCode, instanceId, e.getMessage());
            return null;
        }
    }

    /**
     * Generate DolphinScheduler Web UI URL for workflow definition.
     * Format: http://{host}:{port}/dolphinscheduler/ui/projects/{projectCode}/workflow/definitions/{workflowCode}
     */
    public String getWorkflowDefinitionUrl(Long workflowCode) {
        if (workflowCode == null) {
            return null;
        }
        String baseUrl = getWebuiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            log.warn("dolphin.webui-url is not configured, cannot generate workflow URL");
            return null;
        }
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            log.warn("Cannot generate workflow URL without project code");
            return null;
        }
        return String.format("%s/ui/projects/%d/workflow/definitions/%d",
            baseUrl, projectCode, workflowCode);
    }

    /**
     * Generate DolphinScheduler Web UI URL for task definition.
     * Format: http://{host}:{port}/dolphinscheduler/ui/projects/{projectCode}/task/definitions/{taskCode}
     */
    public String getTaskDefinitionUrl(Long taskCode) {
        if (taskCode == null) {
            return null;
        }
        String baseUrl = getWebuiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            log.warn("dolphin.webui-url is not configured, cannot generate task URL");
            return null;
        }
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            log.warn("Cannot generate task URL without project code");
            return null;
        }
        return String.format("%s/ui/projects/%d/task/definitions/%d",
            baseUrl, projectCode, taskCode);
    }

    /**
     * Return the configured DolphinScheduler Web UI base URL without trailing slashes.
     */
    public String getWebuiBaseUrl() {
        String webuiUrl = properties.getWebuiUrl();
        if (!StringUtils.hasText(webuiUrl)) {
            return null;
        }
        return webuiUrl.replaceAll("/+$", "");
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
            taskPriority, retryTimes, retryInterval, timeoutSeconds, "SHELL", null, null);
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
                                                   String datasourceName,
                                                   String datasourceType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", taskCode);
        payload.put("name", taskName);
        payload.put("version", taskVersion);
        payload.put("description", description == null ? "" : description);
        payload.put("delayTime", 0);
        payload.put("failRetryInterval", Math.max(retryInterval, 1));
        payload.put("failRetryTimes", Math.max(retryTimes, 0));
        payload.put("flag", "YES");
        payload.put("taskPriority", taskPriority);
        payload.put("workerGroup", properties.getWorkerGroup());
        payload.put("environmentCode", -1);
        payload.put("taskType", nodeType == null ? "SHELL" : nodeType);
        payload.put("timeout", timeoutSeconds);
        payload.put("timeoutFlag", timeoutSeconds > 0 ? "OPEN" : "CLOSE");
        payload.put("timeoutNotifyStrategy", "");

        try {
            if ("SQL".equalsIgnoreCase(nodeType)) {
                payload.put("taskParams", objectMapper.writeValueAsString(
                    TaskParams.sql(rawScript, datasourceName, datasourceType)));
            } else {
                payload.put("taskParams", objectMapper.writeValueAsString(TaskParams.shell(rawScript)));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise task parameters for dolphinscheduler-service", e);
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
        location.setX(220 + index * 180);
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
     * Retrieve datasource options from dolphinscheduler-service.
     */
    public List<DolphinDatasourceOption> listDatasources(String type, String keyword) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/api/v1/dolphin/datasources");
        if (StringUtils.hasText(type)) {
            builder.queryParam("type", type);
        }
        if (StringUtils.hasText(keyword)) {
            builder.queryParam("keyword", keyword);
        }

        try {
            JsonNode data = getJson(builder.toUriString());
            JsonNode items = data.path("datasources");
            if (items.isMissingNode() || !items.isArray()) {
                items = data.path("items");
            }

            List<DolphinDatasourceOption> result = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode node : items) {
                    String name = node.path("name").asText(null);
                    if (!StringUtils.hasText(name)) {
                        continue;
                    }
                    DolphinDatasourceOption option = new DolphinDatasourceOption();
                    if (node.hasNonNull("id")) {
                        option.setId(node.path("id").asLong());
                    }
                    option.setName(name);
                    option.setType(node.path("type").asText(null));
                    option.setDbName(node.path("dbName").asText(null));
                    option.setDescription(node.path("description").asText(null));
                    result.add(option);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load datasources from dolphinscheduler-service: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private JsonNode getJson(String path) {
        return getJson(path, DEFAULT_TIMEOUT);
    }

    private JsonNode getJson(String path, Duration timeout) {
        try {
            Mono<String> responseMono = serviceClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class);
            String raw = responseMono.block(timeout);
            return extractDataNode(path, raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to call dolphinscheduler-service GET " + path, e);
        }
    }

    private JsonNode postJson(String path, Map<String, ?> body) {
        return postJson(path, body, DEFAULT_TIMEOUT);
    }

    private JsonNode postJson(String path, Map<String, ?> body, Duration timeout) {
        try {
            String payload = objectMapper.writeValueAsString(body);
            Mono<String> responseMono = serviceClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class);
            String raw = responseMono.block(timeout);
            return extractDataNode(path, raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise request body for dolphinscheduler-service", e);
        }
    }

    private JsonNode extractDataNode(String path, String rawResponse) {
        if (rawResponse == null) {
            throw new IllegalStateException("dolphinscheduler-service returned empty response for " + path);
        }
        try {
            JsonNode node = objectMapper.readTree(rawResponse);
            boolean success = node.path("success").asBoolean(false);
            if (!success) {
                String message = node.path("message").asText("unknown error");
                String code = node.path("code").asText("UNKNOWN");
                throw new IllegalStateException("dolphinscheduler-service error (" + code + "): " + message);
            }
            return node.path("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse dolphinscheduler-service response", e);
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
        private final String datasource;
        private final String sql;
        private final Integer sqlType;
        private final Integer displayRows;

        public static TaskParams shell(String script) {
            return new TaskParams(script, null, null, null);
        }

        public static TaskParams sql(String sql, String datasourceName, String datasourceType) {
            return new TaskParams(null, datasourceName, datasourceType, sql);
        }

        private TaskParams(String rawScript, String datasourceName, String datasourceType, String sql) {
            this.rawScript = rawScript;
            this.datasource = datasourceName;
            this.sql = sql;
            // SQL type: 0=NON_QUERY, 1=QUERY
            this.sqlType = sql != null && sql.trim().toUpperCase().startsWith("SELECT") ? 1 : 0;
            this.displayRows = 10;
            // Don't default to MYSQL - let DolphinScheduler infer type from datasource name
            this.type = datasourceType;
        }
    }
}
