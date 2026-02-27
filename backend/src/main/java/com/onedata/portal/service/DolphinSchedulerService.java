package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinAlertGroupOption;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinEnvironmentOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.*;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
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
            String workflowDescription,
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
                    StringUtils.hasText(workflowDescription) ? workflowDescription.trim() : "",
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
     * Create a DolphinScheduler schedule for the given workflow definition.
     */
    public Long createWorkflowSchedule(long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot create schedule: Project not found");
        }
        Long scheduleId = openApiClient.createSchedule(
                projectCode,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
        log.info("Created Dolphin schedule for workflow {} -> {}", workflowCode, scheduleId);
        return scheduleId;
    }

    /**
     * Update an existing DolphinScheduler schedule.
     */
    public void updateWorkflowSchedule(long scheduleId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot update schedule: Project not found");
        }
        openApiClient.updateSchedule(
                projectCode,
                scheduleId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
        log.info("Updated Dolphin schedule {} for workflow {}", scheduleId, workflowCode);
    }

    /**
     * Online a DolphinScheduler schedule.
     */
    public void onlineWorkflowSchedule(long scheduleId) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot online schedule: Project not found");
        }
        openApiClient.onlineSchedule(projectCode, scheduleId);
        log.info("Onlined Dolphin schedule {}", scheduleId);
    }

    /**
     * Offline a DolphinScheduler schedule.
     */
    public void offlineWorkflowSchedule(long scheduleId) {
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot offline schedule: Project not found");
        }
        openApiClient.offlineSchedule(projectCode, scheduleId);
        log.info("Offlined Dolphin schedule {}", scheduleId);
    }

    /**
     * Query workflow schedule (timing) from DolphinScheduler, if any.
     *
     * <p>
     * Some DolphinScheduler versions only return schedule (timing) info in the
     * process-definition list API, so this method falls back to list paging if
     * the detail API does not include "schedule".
     * </p>
     */
    public DolphinSchedule getWorkflowSchedule(long workflowCode) {
        if (workflowCode <= 0) {
            return null;
        }
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            return null;
        }
        try {
            JsonNode definition = openApiClient.getProcessDefinition(projectCode, workflowCode);
            DolphinSchedule schedule = parseScheduleFromDefinitionNode(definition);
            if (schedule != null) {
                return schedule;
            }

            JsonNode definitionFromList = findProcessDefinitionFromList(projectCode, workflowCode);
            schedule = parseScheduleFromDefinitionNode(definitionFromList);
            if (schedule != null) {
                return schedule;
            }
            return findScheduleFromScheduleList(projectCode, workflowCode);
        } catch (Exception e) {
            log.debug("Failed to query workflow schedule {}: {}", workflowCode, e.getMessage());
            return null;
        }
    }

    private DolphinSchedule parseScheduleFromDefinitionNode(JsonNode definition) {
        if (definition == null) {
            return null;
        }
        JsonNode scheduleNode = definition.path("schedule");
        if (scheduleNode.isMissingNode() || scheduleNode.isNull()) {
            return null;
        }
        try {
            DolphinSchedule schedule = objectMapper.treeToValue(scheduleNode, DolphinSchedule.class);
            if (schedule == null || schedule.getId() == null || schedule.getId() <= 0) {
                return null;
            }
            if (!StringUtils.hasText(schedule.getReleaseState())) {
                String releaseState = definition.path("scheduleReleaseState").asText(null);
                if (StringUtils.hasText(releaseState)) {
                    schedule.setReleaseState(releaseState);
                }
            }
            return schedule;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode findProcessDefinitionFromList(long projectCode, long workflowCode) {
        int pageNo = 1;
        int pageSize = 100;
        int maxPages = 20;

        while (pageNo <= maxPages) {
            JsonNode page = openApiClient.listProcessDefinitions(projectCode, pageNo, pageSize);
            if (page == null) {
                return null;
            }
            JsonNode list = page.path("totalList");
            if (list.isArray()) {
                for (JsonNode node : list) {
                    if (node != null && node.path("code").asLong(-1) == workflowCode) {
                        return node;
                    }
                }
            }

            int totalPage = page.path("totalPage").asInt(0);
            if (totalPage > 0 && pageNo >= totalPage) {
                return null;
            }
            if (!list.isArray() || list.size() < pageSize) {
                return null;
            }
            pageNo++;
        }
        return null;
    }

    private DolphinSchedule findScheduleFromScheduleList(long projectCode, long workflowCode) {
        int pageNo = 1;
        int pageSize = 100;
        int maxPages = 10;

        while (pageNo <= maxPages) {
            DolphinPageData<DolphinSchedule> page = openApiClient.listSchedules(projectCode, pageNo, pageSize, workflowCode);
            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                return null;
            }

            DolphinSchedule unspecifiedCandidate = null;
            for (DolphinSchedule schedule : page.getTotalList()) {
                if (schedule == null || schedule.getId() == null || schedule.getId() <= 0) {
                    continue;
                }
                if (Objects.equals(schedule.getProcessDefinitionCode(), workflowCode)) {
                    return schedule;
                }
                if (schedule.getProcessDefinitionCode() == null && unspecifiedCandidate == null) {
                    unspecifiedCandidate = schedule;
                }
            }
            if (unspecifiedCandidate != null && page.getTotalList().size() == 1) {
                return unspecifiedCandidate;
            }

            if (page.getTotalPage() > 0 && pageNo >= page.getTotalPage()) {
                return null;
            }
            if (page.getTotalList().size() < pageSize) {
                return null;
            }
            pageNo++;
        }
        return null;
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
     * 补数（COMPLEMENT_DATA）执行工作流。
     *
     * <p>
     * DolphinScheduler 3.x 补数通过 executors/start-process-instance 接口实现，scheduleTime 参数传 JSON：
     * 1) 范围：{"complementStartDate":"yyyy-MM-dd HH:mm:ss","complementEndDate":"yyyy-MM-dd HH:mm:ss"}
     * 2) 列表：{"complementScheduleDateList":"...,..."}
     * </p>
     */
    public String backfillProcessInstance(Long workflowCode, WorkflowBackfillRequest request) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Long projectCode = getProjectCode();
        if (projectCode == null) {
            throw new IllegalStateException("Cannot backfill workflow: Project not found");
        }

        String scheduleTime = buildComplementScheduleTime(request);
        String failureStrategy = StringUtils.hasText(request.getFailureStrategy())
                ? request.getFailureStrategy()
                : "CONTINUE";
        String runMode = StringUtils.hasText(request.getRunMode())
                ? request.getRunMode()
                : "RUN_MODE_SERIAL";
        String complementDependentMode = StringUtils.hasText(request.getComplementDependentMode())
                ? request.getComplementDependentMode()
                : "OFF_MODE";
        boolean allLevelDependent = Boolean.TRUE.equals(request.getAllLevelDependent());
        String executionOrder = StringUtils.hasText(request.getExecutionOrder())
                ? request.getExecutionOrder()
                : "DESC_ORDER";

        DolphinConfig config = getConfig();
        JsonNode data = openApiClient.startProcessInstanceRaw(
                projectCode,
                workflowCode,
                scheduleTime,
                failureStrategy,
                "NONE",
                null,
                "COMPLEMENT_DATA",
                config.getWorkerGroup(),
                config.getTenantCode(),
                runMode,
                request.getExpectedParallelismNumber(),
                complementDependentMode,
                allLevelDependent,
                executionOrder);

        String triggerId = data != null ? data.asText() : null;
        if (!StringUtils.hasText(triggerId)) {
            triggerId = "trigger-" + System.currentTimeMillis();
        }
        log.info("Backfill workflow definition {} -> {}", workflowCode, triggerId);
        return triggerId;
    }

    private String buildComplementScheduleTime(WorkflowBackfillRequest request) {
        Map<String, String> payload = new HashMap<>();
        String mode = StringUtils.hasText(request.getMode()) ? request.getMode() : "range";

        if ("list".equalsIgnoreCase(mode)) {
            if (!StringUtils.hasText(request.getScheduleDateList())) {
                throw new IllegalArgumentException("scheduleDateList is required when mode=list");
            }
            payload.put("complementScheduleDateList", request.getScheduleDateList());
        } else {
            if (!StringUtils.hasText(request.getStartTime()) || !StringUtils.hasText(request.getEndTime())) {
                throw new IllegalArgumentException("startTime/endTime is required when mode=range");
            }
            payload.put("complementStartDate", request.getStartTime());
            payload.put("complementEndDate", request.getEndTime());
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize complement scheduleTime", e);
        }
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
        if (projectCode == null) {
            return Collections.emptyList();
        }

        int targetLimit = Math.min(Math.max(limit, 1), 100);
        int pageSize = Math.min(Math.max(targetLimit * 3, 20), 100);

        List<DolphinProcessInstance> filtered = collectWorkflowInstances(
                projectCode, workflowCode, targetLimit, pageSize, true);

        // Fallback: if DS ignores server-side filter, scan global pages and filter locally.
        if (filtered.isEmpty()) {
            filtered = collectWorkflowInstances(projectCode, workflowCode, targetLimit, pageSize, false);
        }

        if (filtered.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorkflowInstanceSummary> result = new ArrayList<>();
        for (DolphinProcessInstance instance : filtered) {
            if (result.size() >= targetLimit) {
                break;
            }
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

    private List<DolphinProcessInstance> collectWorkflowInstances(Long projectCode,
            Long workflowCode,
            int limit,
            int pageSize,
            boolean useServerFilter) {
        List<DolphinProcessInstance> matched = new ArrayList<>();
        int pageNo = 1;
        int maxPages = 5;

        while (pageNo <= maxPages && matched.size() < limit) {
            DolphinPageData<DolphinProcessInstance> page = openApiClient.listProcessInstances(
                    projectCode,
                    pageNo,
                    pageSize,
                    useServerFilter ? workflowCode : null);

            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                break;
            }

            for (DolphinProcessInstance instance : page.getTotalList()) {
                if (instance == null || instance.getId() == null) {
                    continue;
                }
                if (isWorkflowInstance(projectCode, workflowCode, instance)) {
                    matched.add(instance);
                    if (matched.size() >= limit) {
                        break;
                    }
                }
            }

            if (shouldStopPaging(page, pageNo, pageSize)) {
                break;
            }
            pageNo++;
        }

        return matched;
    }

    private boolean shouldStopPaging(DolphinPageData<DolphinProcessInstance> page,
            int currentPage,
            int pageSize) {
        if (page.getTotalPage() > 0 && currentPage >= page.getTotalPage()) {
            return true;
        }
        return page.getTotalList() == null || page.getTotalList().size() < pageSize;
    }

    private boolean isWorkflowInstance(Long projectCode, Long workflowCode, DolphinProcessInstance instance) {
        if (Objects.equals(instance.getProcessDefinitionCode(), workflowCode)) {
            return true;
        }
        if (instance.getProcessDefinitionCode() != null) {
            return false;
        }
        try {
            DolphinProcessInstance detail = openApiClient.getProcessInstance(projectCode, instance.getId());
            return detail != null && Objects.equals(detail.getProcessDefinitionCode(), workflowCode);
        } catch (Exception ex) {
            return false;
        }
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

    public String getDefaultTenantCode() {
        DolphinConfig config = getConfig();
        return StringUtils.hasText(config.getTenantCode()) ? config.getTenantCode() : "default";
    }

    public String getDefaultWorkerGroup() {
        DolphinConfig config = getConfig();
        return StringUtils.hasText(config.getWorkerGroup()) ? config.getWorkerGroup() : "default";
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
            Long currentProjectCode = getProjectCode();
            DolphinPageData<DolphinTaskGroup> page = openApiClient.listTaskGroups(1, 200, keyword, null);
            if (page == null || page.getTotalList() == null) {
                return Collections.emptyList();
            }
            boolean hasProjectCode = page.getTotalList().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(group -> group.getProjectCode() != null);
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
                if (hasProjectCode && currentProjectCode != null) {
                    Long groupProjectCode = group.getProjectCode();
                    // projectCode=0 视为全局任务组，允许跨项目复用；其余项目级任务组需与当前项目一致。
                    if (groupProjectCode != null
                            && groupProjectCode > 0
                            && !Objects.equals(currentProjectCode, groupProjectCode)) {
                        continue;
                    }
                }
                DolphinTaskGroupOption option = new DolphinTaskGroupOption();
                option.setId(group.getId());
                option.setProjectCode(group.getProjectCode());
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

    /**
     * Retrieve worker group list from DolphinScheduler (project scoped).
     */
    public List<String> listWorkerGroups() {
        try {
            Long projectCode = getProjectCode();
            if (projectCode == null) {
                return Collections.emptyList();
            }
            return openApiClient.listProjectWorkerGroups(projectCode);
        } catch (Exception ex) {
            log.warn("Failed to load worker groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve tenant code list from DolphinScheduler.
     */
    public List<String> listTenants() {
        try {
            return openApiClient.listTenants();
        } catch (Exception ex) {
            log.warn("Failed to load tenants from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve alert group list from DolphinScheduler.
     */
    public List<DolphinAlertGroupOption> listAlertGroups() {
        try {
            return openApiClient.listAlertGroups();
        } catch (Exception ex) {
            log.warn("Failed to load alert groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve environment list from DolphinScheduler.
     */
    public List<DolphinEnvironmentOption> listEnvironments() {
        try {
            return openApiClient.listEnvironments();
        } catch (Exception ex) {
            log.warn("Failed to load environments from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Preview next trigger times for a schedule JSON.
     */
    public List<String> previewSchedule(String scheduleJson) {
        try {
            Long projectCode = getProjectCode();
            if (projectCode == null) {
                return Collections.emptyList();
            }
            return openApiClient.previewSchedule(projectCode, scheduleJson);
        } catch (Exception ex) {
            log.warn("Failed to preview schedule from DolphinScheduler: {}", ex.getMessage());
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
