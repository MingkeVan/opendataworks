package com.onedata.portal.service.dolphin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.DolphinSchedulerProperties;
import com.onedata.portal.dto.dolphin.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct client for DolphinScheduler OpenAPI.
 * Handles authentication, request building, and response parsing.
 */
@Slf4j
@Component
public class DolphinOpenApiClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final DolphinSchedulerProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DolphinOpenApiClient(DolphinSchedulerProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        String baseUrl = properties.getUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("DolphinScheduler URL is not configured (dolphin.url)");
        }

        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("token", properties.getToken())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
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

            JsonNode data = postForm(path, formData);
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

    // --- Private Helpers ---

    private JsonNode getWithParams(String path, MultiValueMap<String, String> queryParams) {
        return executeRequest(webClient.get().uri(uriBuilder -> {
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
        return executeRequest(webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData)));
    }

    private JsonNode putForm(String path, MultiValueMap<String, String> formData) {
        return executeRequest(webClient.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData)));
    }

    private JsonNode delete(String path) {
        return executeRequest(webClient.delete().uri(path));
    }

    private JsonNode executeRequest(WebClient.RequestHeadersSpec<?> requestSpec) {
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
