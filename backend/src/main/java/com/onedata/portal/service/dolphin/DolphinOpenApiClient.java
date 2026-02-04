package com.onedata.portal.service.dolphin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinAlertGroupOption;
import com.onedata.portal.dto.DolphinEnvironmentOption;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.DolphinConfigService;
import com.onedata.portal.dto.dolphin.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct client for DolphinScheduler OpenAPI.
 * Handles authentication, request building, and response parsing.
 * Now supports dynamic configuration via DolphinConfigService.
 */
@Slf4j
@Component
public class DolphinOpenApiClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final DolphinConfigService dolphinConfigService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    // Cache the WebClient instance to avoid recreating it for every request
    // Key: url + token
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();

    public DolphinOpenApiClient(DolphinConfigService dolphinConfigService,
            ObjectMapper objectMapper,
            WebClient.Builder builder) {
        this.dolphinConfigService = dolphinConfigService;
        this.objectMapper = objectMapper;
        this.webClientBuilder = builder;
    }

    private WebClient getWebClient() {
        DolphinConfig config = dolphinConfigService.getActiveConfig();
        if (config == null || !StringUtils.hasText(config.getUrl())) {
            throw new IllegalStateException("DolphinScheduler configuration is missing or URL is empty");
        }
        return getWebClient(config);
    }

    private WebClient getWebClient(DolphinConfig config) {
        String key = config.getUrl() + "::" + config.getToken();
        return webClientCache.computeIfAbsent(key, k -> {
            log.info("Creating new WebClient for DolphinScheduler at {}", config.getUrl());
            return webClientBuilder.clone() // Clone builder to avoid side effects
                    .baseUrl(config.getUrl())
                    .defaultHeader("token", config.getToken())
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .build();
        });
    }

    /**
     * Test connection with provided configuration.
     */
    public boolean testConnection(DolphinConfig config) {
        try {
            if (config == null || !StringUtils.hasText(config.getUrl())) {
                return false;
            }
            // Use a temporary WebClient for testing
            WebClient client = webClientBuilder.clone()
                    .baseUrl(config.getUrl())
                    .defaultHeader("token", config.getToken())
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .build();

            // Try to list projects (lightweight) or just checking system info if possible.
            // Using list projects as it's common.
            String path = "/projects";
            Map<String, String> params = new HashMap<>();
            params.put("pageNo", "1");
            params.put("pageSize", "1");

            MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
            params.forEach(multiMap::add);

            JsonNode result = executeRequest(client, client.get().uri(uriBuilder -> {
                uriBuilder.path(path);
                uriBuilder.queryParams(multiMap);
                return uriBuilder.build();
            }));

            return result != null;
        } catch (Exception e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Query project code by name.
     */
    public DolphinProject getProject(String projectName) {
        try {
            String path = "/projects";
            Map<String, String> params = new HashMap<>();
            params.put("searchVal", projectName);
            params.put("pageNo", "1");
            params.put("pageSize", "100");
            JsonNode data = get(path, params);

            if (data == null)
                return null;

            // API returns a page structure or list
            JsonNode totalList = data.path("totalList");
            if (totalList.isArray()) {
                for (JsonNode node : totalList) {
                    if (projectName.equals(node.path("name").asText())) {
                        return objectMapper.treeToValue(node, DolphinProject.class);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get project {}", projectName, e);
            throw new RuntimeException("Failed to get project: " + e.getMessage());
        }
    }

    /**
     * Create a new project.
     */
    public Long createProject(String projectName, String description) {
        try {
            String path = "/projects";
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("projectName", projectName);
            formData.add("description", description != null ? description : "");

            JsonNode data = postForm(path, formData);
            return data.path("code").asLong();
        } catch (Exception e) {
            log.error("Failed to create project {}", projectName, e);
            throw new RuntimeException("Failed to create project: " + e.getMessage());
        }
    }

    /**
     * List datasources.
     */
    public List<DolphinDatasource> listDatasources(Integer pageNo, Integer pageSize) {
        try {
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("pageNo", String.valueOf(pageNo != null ? pageNo : 1));
            queryParams.add("pageSize", String.valueOf(pageSize != null ? pageSize : 100));

            JsonNode data = getWithParams("/datasources", queryParams);
            if (data == null)
                return Collections.emptyList();

            JsonNode list = data.path("totalList");
            return objectMapper.readValue(list.traverse(), new TypeReference<List<DolphinDatasource>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to list datasources", e);
            return Collections.emptyList();
        }
    }

    /**
     * List task groups.
     */
    public DolphinPageData<DolphinTaskGroup> listTaskGroups(Integer pageNo,
            Integer pageSize,
            String name,
            Integer status) {
        try {
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("pageNo", String.valueOf(pageNo != null ? pageNo : 1));
            queryParams.add("pageSize", String.valueOf(pageSize != null ? pageSize : 100));
            if (StringUtils.hasText(name)) {
                queryParams.add("name", name);
            }
            if (status != null) {
                queryParams.add("status", String.valueOf(status));
            }

            JsonNode data = getWithParams("/task-group/list-paging", queryParams);
            if (data == null) {
                return new DolphinPageData<>();
            }
            return objectMapper.readerFor(new TypeReference<DolphinPageData<DolphinTaskGroup>>() {
            }).readValue(data);
        } catch (Exception e) {
            log.warn("Failed to list task groups", e);
            return new DolphinPageData<>();
        }
    }

    /**
     * List worker groups assigned to the given project.
     *
     * <p>
     * Endpoint: GET /projects/{projectCode}/worker-group
     * </p>
     */
    public List<String> listProjectWorkerGroups(long projectCode) {
        try {
            String path = String.format("/projects/%d/worker-group", projectCode);
            JsonNode data = get(path, null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode node : data) {
                String workerGroup = node.path("workerGroup").asText(null);
                if (StringUtils.hasText(workerGroup)) {
                    result.add(workerGroup);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list project worker groups for {}", projectCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * List tenants.
     *
     * <p>
     * Endpoint: GET /tenants/list
     * </p>
     */
    public List<String> listTenants() {
        try {
            JsonNode data = get("/tenants/list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode node : data) {
                String tenantCode = node.path("tenantCode").asText(null);
                if (StringUtils.hasText(tenantCode)) {
                    result.add(tenantCode);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list tenants", e);
            return Collections.emptyList();
        }
    }

    /**
     * List alert groups.
     *
     * <p>
     * Endpoint: GET /alert-groups/list
     * </p>
     */
    public List<DolphinAlertGroupOption> listAlertGroups() {
        try {
            JsonNode data = get("/alert-groups/list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(data.traverse(), new TypeReference<List<DolphinAlertGroupOption>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to list alert groups", e);
            return Collections.emptyList();
        }
    }

    /**
     * List environments.
     *
     * <p>
     * Endpoint: GET /environment/query-environment-list
     * </p>
     */
    public List<DolphinEnvironmentOption> listEnvironments() {
        try {
            JsonNode data = get("/environment/query-environment-list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(data.traverse(), new TypeReference<List<DolphinEnvironmentOption>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to list environments", e);
            return Collections.emptyList();
        }
    }

    /**
     * Preview next trigger times for a given schedule.
     *
     * <p>
     * Endpoint: POST /projects/{projectCode}/schedules/preview
     * Body: {"schedule": "{...json...}"}
     * </p>
     */
    public List<String> previewSchedule(long projectCode, String scheduleJson) {
        try {
            String path = String.format("/projects/%d/schedules/preview", projectCode);
            Map<String, Object> payload = new HashMap<>();
            payload.put("schedule", StringUtils.hasText(scheduleJson) ? scheduleJson : "{}");
            JsonNode data = postJson(path, payload);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(data.traverse(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to preview schedule for project {}", projectCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * Create or update process definition.
     * Note: DS 3.x uses different endpoints for create and update.
     */
    public Long createOrUpdateProcessDefinition(long projectCode,
            String name,
            String description,
            String tenantCode,
            String executionType,
            String taskRelationJson,
            String taskDefinitionJson,
            String locations,
            String globalParams,
            Long existingCode) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("name", name);
        formData.add("description", description);
        formData.add("tenantCode", tenantCode);
        formData.add("executionType", executionType);
        formData.add("taskRelationJson", taskRelationJson);
        formData.add("taskDefinitionJson", taskDefinitionJson);
        if (StringUtils.hasText(locations)) {
            formData.add("locations", locations);
        } else {
            // Provide empty locations to avoid errors if required
            formData.add("locations", "[]");
        }
        if (StringUtils.hasText(globalParams)) {
            formData.add("globalParams", globalParams);
        } else {
            formData.add("globalParams", "[]");
        }

        try {
            if (existingCode != null && existingCode > 0) {
                // Update
                String path = String.format("/projects/%d/process-definition/%d", projectCode, existingCode);
                formData.add("releaseState", "OFFLINE"); // Ensure offline before update
                putForm(path, formData);
                return existingCode;
            } else {
                // Create
                String path = String.format("/projects/%d/process-definition", projectCode);
                JsonNode data = postForm(path, formData);
                return data.path("code").asLong();
            }
        } catch (Exception e) {
            log.error("Failed to save process definition {}", name, e);
            throw new RuntimeException("Failed to save process definition: " + e.getMessage());
        }
    }

    /**
     * Release process definition (ONLINE/OFFLINE).
     */
    public void releaseProcessDefinition(long projectCode, long processCode, String state) {
        try {
            String path = String.format("/projects/%d/process-definition/%d/release", projectCode, processCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("name", ""); // Some versions require name param even if unused
            formData.add("releaseState", state);
            postForm(path, formData);
        } catch (Exception e) {
            log.error("Failed to release process {} to {}", processCode, state, e);
            throw new RuntimeException("Failed to update release state: " + e.getMessage());
        }
    }

    /**
     * Query process definition.
     */
    public JsonNode getProcessDefinition(long projectCode, long processCode) {
        try {
            String path = String.format("/projects/%d/process-definition/%d", projectCode, processCode);
            return executeRequest(getWebClient(), getWebClient().get().uri(path));
        } catch (Exception e) {
            log.error("Failed to query process definition {}", processCode, e);
            throw new RuntimeException("Failed to query process definition: " + e.getMessage());
        }
    }

    /**
     * Start process instance.
     */
    public Long startProcessInstance(long projectCode,
            long processDefinitionCode,
            String scheduleTime,
            String failureStrategy,
            String warningType,
            String warningGroupId,
            String execType,
            String workerGroup,
            String tenantCode) {
        try {
            JsonNode data = startProcessInstanceRaw(projectCode,
                    processDefinitionCode,
                    scheduleTime,
                    failureStrategy,
                    warningType,
                    warningGroupId,
                    execType,
                    workerGroup,
                    tenantCode,
                    null,
                    null,
                    null,
                    null,
                    null);
            if (data == null) {
                return null;
            }
            // Different DS versions expose either processInstanceCode or id
            if (data.hasNonNull("processInstanceCode")) {
                return data.path("processInstanceCode").asLong();
            }
            if (data.hasNonNull("id")) {
                return data.path("id").asLong();
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to start process definition {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to start process: " + e.getMessage());
        }
    }

    /**
     * Start process instance with extended options.
     *
     * <p>
     * For DolphinScheduler 3.x, complement data (补数) is also triggered via
     * this endpoint with execType=COMPLEMENT_DATA.
     * </p>
     */
    public JsonNode startProcessInstanceRaw(long projectCode,
            long processDefinitionCode,
            String scheduleTime,
            String failureStrategy,
            String warningType,
            String warningGroupId,
            String execType,
            String workerGroup,
            String tenantCode,
            String runMode,
            Integer expectedParallelismNumber,
            String complementDependentMode,
            Boolean allLevelDependent,
            String executionOrder) {
        try {
            String path = String.format("/projects/%d/executors/start-process-instance", projectCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("processDefinitionCode", String.valueOf(processDefinitionCode));
            formData.add("scheduleTime", scheduleTime != null ? scheduleTime : "");
            formData.add("failureStrategy", failureStrategy != null ? failureStrategy : "CONTINUE");
            formData.add("warningType", warningType != null ? warningType : "NONE");
            formData.add("warningGroupId", warningGroupId != null ? warningGroupId : "0");
            formData.add("execType", execType != null ? execType : "START_PROCESS");
            formData.add("workerGroup", workerGroup != null ? workerGroup : "default");
            formData.add("tenantCode", tenantCode != null ? tenantCode : "default");
            formData.add("dryRun", "0");

            if (StringUtils.hasText(runMode)) {
                formData.add("runMode", runMode);
            }
            if (expectedParallelismNumber != null) {
                formData.add("expectedParallelismNumber", String.valueOf(expectedParallelismNumber));
            }
            if (StringUtils.hasText(complementDependentMode)) {
                formData.add("complementDependentMode", complementDependentMode);
            }
            if (allLevelDependent != null) {
                formData.add("allLevelDependent", String.valueOf(allLevelDependent));
            }
            if (StringUtils.hasText(executionOrder)) {
                formData.add("executionOrder", executionOrder);
            }

            return postForm(path, formData);
        } catch (Exception e) {
            log.error("Failed to start process instance {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to start process: " + e.getMessage());
        }
    }

    /**
     * List process instances.
     */
    public DolphinPageData<DolphinProcessInstance> listProcessInstances(long projectCode,
            int pageNo,
            int pageSize,
            Long processDefinitionCode) {
        try {
            String path = String.format("/projects/%d/process-instances", projectCode);
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("pageNo", String.valueOf(pageNo));
            query.add("pageSize", String.valueOf(pageSize));
            if (processDefinitionCode != null) {
                // API expects processDefinitionCode, not processDefineCode
                query.add("processDefinitionCode", String.valueOf(processDefinitionCode));
            }

            JsonNode data = getWithParams(path, query);
            if (data == null)
                return new DolphinPageData<>();

            return objectMapper.readerFor(new TypeReference<DolphinPageData<DolphinProcessInstance>>() {
            })
                    .readValue(data);
        } catch (Exception e) {
            log.warn("Failed to list process instances", e);
            return new DolphinPageData<>();
        }
    }

    /**
     * Get single process instance.
     */
    public DolphinProcessInstance getProcessInstance(long projectCode, long instanceId) {
        try {
            String path = String.format("/projects/%d/process-instances/%d", projectCode, instanceId);
            JsonNode data = get(path, null);
            return objectMapper.treeToValue(data, DolphinProcessInstance.class);
        } catch (Exception e) {
            log.warn("Failed to get process instance {}", instanceId, e);
            return null;
        }
    }

    /**
     * Delete process definition.
     */
    public void deleteProcessDefinition(long projectCode, long processDefinitionCode) {
        try {
            String path = String.format("/projects/%d/process-definition/%d", projectCode, processDefinitionCode);
            delete(path);
        } catch (Exception e) {
            log.error("Failed to delete process definition {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to delete process: " + e.getMessage());
        }
    }

    /**
     * Create a schedule for a workflow definition.
     *
     * <p>
     * Endpoint: POST /projects/{projectCode}/schedules
     * Params (version dependent):
     * - DS 3.x commonly uses workflowDefinitionCode
     * - DS 2.x commonly uses processDefinitionCode
     * To be compatible across versions we send both keys with the same value.
     *
     * Additional commonly-required form fields in the WebUI:
     * processInstancePriority, workerGroup, tenantCode, environmentCode, warningGroupId
     * </p>
     *
     * @return schedule id if available
     */
    public Long createSchedule(long projectCode,
            long workflowDefinitionCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        try {
            String path = String.format("/projects/%d/schedules", projectCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            String definitionCode = String.valueOf(workflowDefinitionCode);
            // DS 3.x
            formData.add("workflowDefinitionCode", definitionCode);
            // DS 2.x
            formData.add("processDefinitionCode", definitionCode);
            formData.add("schedule", scheduleJson != null ? scheduleJson : "{}");
            formData.add("warningType", StringUtils.hasText(warningType) ? warningType : "NONE");
            formData.add("failureStrategy", StringUtils.hasText(failureStrategy) ? failureStrategy : "CONTINUE");
            formData.add("warningGroupId", String.valueOf(warningGroupId != null ? warningGroupId : 0L));

            DolphinConfig config = dolphinConfigService.getActiveConfig();
            String resolvedTenantCode = StringUtils.hasText(tenantCode) ? tenantCode.trim()
                    : (config != null && StringUtils.hasText(config.getTenantCode()) ? config.getTenantCode()
                            : "default");
            String resolvedWorkerGroup = StringUtils.hasText(workerGroup) ? workerGroup.trim()
                    : (config != null && StringUtils.hasText(config.getWorkerGroup()) ? config.getWorkerGroup()
                            : "default");
            String resolvedPriority = StringUtils.hasText(processInstancePriority) ? processInstancePriority.trim()
                    : "MEDIUM";
            Long resolvedEnvironmentCode = environmentCode != null ? environmentCode : -1L;

            formData.add("processInstancePriority", resolvedPriority);
            formData.add("workflowInstancePriority", resolvedPriority);
            formData.add("workerGroup", resolvedWorkerGroup);
            formData.add("tenantCode", resolvedTenantCode);
            // -1 means default/no environment in DolphinScheduler
            formData.add("environmentCode", String.valueOf(resolvedEnvironmentCode));

            JsonNode data = postForm(path, formData);
            if (data == null) {
                return null;
            }
            if (data.isIntegralNumber()) {
                return data.asLong();
            }
            if (data.hasNonNull("id")) {
                return data.path("id").asLong();
            }
            if (data.hasNonNull("scheduleId")) {
                return data.path("scheduleId").asLong();
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to create schedule for workflow {}", workflowDefinitionCode, e);
            throw new RuntimeException("Failed to create schedule: " + e.getMessage());
        }
    }

    /**
     * Update an existing schedule.
     *
     * <p>
     * Endpoint: PUT /projects/{projectCode}/schedules/{id}
     * </p>
     */
    public void updateSchedule(long projectCode,
            long scheduleId,
            long workflowDefinitionCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        try {
            String path = String.format("/projects/%d/schedules/%d", projectCode, scheduleId);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("id", String.valueOf(scheduleId));
            String definitionCode = String.valueOf(workflowDefinitionCode);
            // DS 3.x
            formData.add("workflowDefinitionCode", definitionCode);
            // DS 2.x
            formData.add("processDefinitionCode", definitionCode);
            formData.add("schedule", scheduleJson != null ? scheduleJson : "{}");
            formData.add("warningType", StringUtils.hasText(warningType) ? warningType : "NONE");
            formData.add("failureStrategy", StringUtils.hasText(failureStrategy) ? failureStrategy : "CONTINUE");
            formData.add("warningGroupId", String.valueOf(warningGroupId != null ? warningGroupId : 0L));

            DolphinConfig config = dolphinConfigService.getActiveConfig();
            String resolvedTenantCode = StringUtils.hasText(tenantCode) ? tenantCode.trim()
                    : (config != null && StringUtils.hasText(config.getTenantCode()) ? config.getTenantCode()
                            : "default");
            String resolvedWorkerGroup = StringUtils.hasText(workerGroup) ? workerGroup.trim()
                    : (config != null && StringUtils.hasText(config.getWorkerGroup()) ? config.getWorkerGroup()
                            : "default");
            String resolvedPriority = StringUtils.hasText(processInstancePriority) ? processInstancePriority.trim()
                    : "MEDIUM";
            Long resolvedEnvironmentCode = environmentCode != null ? environmentCode : -1L;

            formData.add("processInstancePriority", resolvedPriority);
            formData.add("workflowInstancePriority", resolvedPriority);
            formData.add("workerGroup", resolvedWorkerGroup);
            formData.add("tenantCode", resolvedTenantCode);
            formData.add("environmentCode", String.valueOf(resolvedEnvironmentCode));

            putForm(path, formData);
        } catch (Exception e) {
            log.error("Failed to update schedule {} for workflow {}", scheduleId, workflowDefinitionCode, e);
            throw new RuntimeException("Failed to update schedule: " + e.getMessage());
        }
    }

    /**
     * Online a schedule.
     */
    public void onlineSchedule(long projectCode, long scheduleId) {
        try {
            String path = String.format("/projects/%d/schedules/%d/online", projectCode, scheduleId);
            post(path);
        } catch (Exception e) {
            log.error("Failed to online schedule {}", scheduleId, e);
            throw new RuntimeException("Failed to online schedule: " + e.getMessage());
        }
    }

    /**
     * Offline a schedule.
     */
    public void offlineSchedule(long projectCode, long scheduleId) {
        try {
            String path = String.format("/projects/%d/schedules/%d/offline", projectCode, scheduleId);
            post(path);
        } catch (Exception e) {
            log.error("Failed to offline schedule {}", scheduleId, e);
            throw new RuntimeException("Failed to offline schedule: " + e.getMessage());
        }
    }

    // --- Private Helpers ---

    private JsonNode getWithParams(String path, MultiValueMap<String, String> queryParams) {
        return executeRequest(getWebClient(), getWebClient().get().uri(uriBuilder -> {
            uriBuilder.path(path);
            if (queryParams != null) {
                uriBuilder.queryParams(queryParams);
            }
            return uriBuilder.build();
        }));
    }

    private JsonNode get(String path, Map<String, String> queryParams) {
        MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
        if (queryParams != null) {
            queryParams.forEach(multiMap::add);
        }
        return getWithParams(path, multiMap);
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> formData) {
        return executeRequest(getWebClient(), getWebClient().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData)));
    }

    private JsonNode postJson(String path, Object payload) {
        return executeRequest(getWebClient(), getWebClient().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private JsonNode post(String path) {
        return executeRequest(getWebClient(), getWebClient().post().uri(path));
    }

    private JsonNode putForm(String path, MultiValueMap<String, String> formData) {
        return executeRequest(getWebClient(), getWebClient().put()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData)));
    }

    private JsonNode delete(String path) {
        return executeRequest(getWebClient(), getWebClient().delete().uri(path));
    }

    private JsonNode executeRequest(WebClient client, WebClient.RequestHeadersSpec<?> requestSpec) {
        try {
            String response = requestSpec.retrieve()
                    .bodyToMono(String.class)
                    .timeout(DEFAULT_TIMEOUT)
                    .block();

            if (!StringUtils.hasText(response))
                return null;

            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = root.path("msg").asText("Unknown error");
                throw new RuntimeException("API Error " + code + ": " + msg);
            }
            return root.path("data");
        } catch (Exception e) {
            // Wrap in RuntimeException to keep signatures clean
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
