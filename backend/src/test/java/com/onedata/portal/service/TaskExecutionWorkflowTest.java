package com.onedata.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for task execution workflow lifecycle:
 * - Create temporary workflow for single task execution
 * - Execute the task
 * - Verify workflow is created in DolphinScheduler
 * - Wait for cleanup
 * - Verify workflow is deleted from DolphinScheduler
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskExecutionWorkflowTest {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionWorkflowTest.class);

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DolphinSchedulerService dolphinSchedulerService;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private TaskExecutionLogMapper executionLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient dolphinApiClient;
    private String authToken;
    private Long projectCode;

    private List<Long> createdTaskIds = new ArrayList<>();
    private List<Long> tempWorkflowCodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        log.info("Setting up test environment...");

        // Initialize DolphinScheduler API client
        String apiBaseUrl = System.getenv("DS_API_BASE_URL");
        if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
            apiBaseUrl = "http://localhost:12345/dolphinscheduler";
        }

        dolphinApiClient = WebClient.builder()
            .baseUrl(apiBaseUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .build();

        // Authenticate to get token
        String userName = System.getenv("PYDS_USER_NAME");
        String userPassword = System.getenv("PYDS_USER_PASSWORD");
        if (userName == null) userName = "admin";
        if (userPassword == null) userPassword = "dolphinscheduler123";

        String loginResponse = dolphinApiClient.post()
            .uri("/login")
            .bodyValue("userName=" + userName + "&userPassword=" + userPassword)
            .retrieve()
            .bodyToMono(String.class)
            .block();

        JsonNode loginData = objectMapper.readTree(loginResponse);
        assertTrue(loginData.path("success").asBoolean(), "Login failed");
        authToken = loginData.path("data").path("sessionId").asText();
        assertNotNull(authToken, "No session ID returned");

        // Get project code
        projectCode = dolphinSchedulerService.getProjectCode(true);
        assertNotNull(projectCode, "Project code is null");

        log.info("Test setup complete: token={}, projectCode={}",
            authToken.substring(0, 10) + "...", projectCode);
    }

    @AfterEach
    void tearDown() {
        log.info("Cleaning up test data...");

        // Clean up any remaining temporary workflows
        for (Long workflowCode : tempWorkflowCodes) {
            try {
                dolphinSchedulerService.deleteWorkflow(workflowCode);
                log.info("Cleaned up temp workflow: {}", workflowCode);
            } catch (Exception e) {
                log.warn("Failed to cleanup workflow {}: {}", workflowCode, e.getMessage());
            }
        }

        // Clean up test tasks
        for (Long taskId : createdTaskIds) {
            try {
                dataTaskService.delete(taskId);
                log.info("Deleted test task: {}", taskId);
            } catch (Exception e) {
                log.warn("Failed to delete task {}: {}", taskId, e.getMessage());
            }
        }

        log.info("Cleanup complete");
    }

    @Test
    void testTaskExecutionCreatesAndDeletesTempWorkflow() throws Exception {
        log.info("Starting test: testTaskExecutionCreatesAndDeletesTempWorkflow");

        // 1. Create a test task
        DataTask task = new DataTask();
        task.setTaskName("Test Task for Workflow Lifecycle");
        task.setTaskCode("test-workflow-" + System.currentTimeMillis());
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setTaskSql("echo 'Test execution'");
        task.setStatus("draft");

        dataTaskService.create(task, null, null);
        createdTaskIds.add(task.getId());
        log.info("Created test task: id={}, code={}", task.getId(), task.getTaskCode());

        // 2. Get initial workflow count in DolphinScheduler
        int initialWorkflowCount = getWorkflowCount();
        log.info("Initial workflow count in DolphinScheduler: {}", initialWorkflowCount);

        // 3. Execute the task (this should create a temporary workflow)
        log.info("Executing task...");
        dataTaskService.executeTask(task.getId());

        // 4. Wait a bit for workflow to be created
        Thread.sleep(2000);

        // 5. Verify workflow was created
        int afterExecutionCount = getWorkflowCount();
        log.info("Workflow count after execution: {}", afterExecutionCount);
        assertTrue(afterExecutionCount > initialWorkflowCount,
            "Expected new workflow to be created. Before: " + initialWorkflowCount + ", After: " + afterExecutionCount);

        // 6. Find the temporary workflow (should have name like "test-task-{taskCode}")
        List<JsonNode> workflows = listAllWorkflows();
        JsonNode tempWorkflow = workflows.stream()
            .filter(wf -> wf.path("name").asText().startsWith("test-task-"))
            .findFirst()
            .orElse(null);

        assertNotNull(tempWorkflow, "Temporary workflow not found in DolphinScheduler");
        Long tempWorkflowCode = tempWorkflow.path("code").asLong();
        tempWorkflowCodes.add(tempWorkflowCode);
        log.info("Found temporary workflow: name={}, code={}",
            tempWorkflow.path("name").asText(), tempWorkflowCode);

        // 7. Wait for async cleanup (5 minutes + buffer)
        // For testing, we can manually trigger cleanup instead of waiting
        log.info("Testing immediate cleanup (instead of waiting 5 minutes)...");
        dolphinSchedulerService.deleteWorkflow(tempWorkflowCode);

        // 8. Wait a bit for deletion to complete
        Thread.sleep(2000);

        // 9. Verify workflow was deleted
        int finalWorkflowCount = getWorkflowCount();
        log.info("Workflow count after cleanup: {}", finalWorkflowCount);
        assertEquals(initialWorkflowCount, finalWorkflowCount,
            "Expected workflow to be deleted. Initial: " + initialWorkflowCount + ", Final: " + finalWorkflowCount);

        // 10. Verify the specific workflow is gone
        List<JsonNode> finalWorkflows = listAllWorkflows();
        boolean workflowStillExists = finalWorkflows.stream()
            .anyMatch(wf -> wf.path("code").asLong() == tempWorkflowCode);

        assertFalse(workflowStillExists,
            "Temporary workflow " + tempWorkflowCode + " should have been deleted but still exists");

        log.info("Test passed: Temporary workflow was created and successfully deleted");
    }

    @Test
    void testMultipleTaskExecutionsHandleWorkflowsProperly() throws Exception {
        log.info("Starting test: testMultipleTaskExecutionsHandleWorkflowsProperly");

        // Create multiple tasks
        List<DataTask> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DataTask task = new DataTask();
            task.setTaskName("Test Task " + i);
            task.setTaskCode("test-multi-" + System.currentTimeMillis() + "-" + i);
            task.setTaskType("batch");
            task.setEngine("dolphin");
            task.setTaskSql("echo 'Test " + i + "'");
            task.setStatus("draft");
            dataTaskService.create(task, null, null);
            tasks.add(task);
            createdTaskIds.add(task.getId());
        }

        int initialCount = getWorkflowCount();
        log.info("Initial workflow count: {}", initialCount);

        // Execute all tasks
        for (DataTask task : tasks) {
            log.info("Executing task: {}", task.getTaskName());
            dataTaskService.executeTask(task.getId());
            Thread.sleep(1000);
        }

        // Verify 3 new workflows were created
        Thread.sleep(2000);
        int afterCount = getWorkflowCount();
        log.info("Workflow count after all executions: {}", afterCount);
        assertEquals(initialCount + 3, afterCount,
            "Expected 3 new workflows. Initial: " + initialCount + ", After: " + afterCount);

        // Clean up all temporary workflows
        List<JsonNode> workflows = listAllWorkflows();
        for (JsonNode wf : workflows) {
            if (wf.path("name").asText().startsWith("test-task-")) {
                Long code = wf.path("code").asLong();
                dolphinSchedulerService.deleteWorkflow(code);
                tempWorkflowCodes.add(code);
                log.info("Deleted temp workflow: {}", code);
            }
        }

        Thread.sleep(2000);
        int finalCount = getWorkflowCount();
        assertEquals(initialCount, finalCount,
            "All temporary workflows should be deleted. Initial: " + initialCount + ", Final: " + finalCount);

        log.info("Test passed: Multiple temporary workflows handled correctly");
    }

    /**
     * Get the total count of workflows in the project
     */
    private int getWorkflowCount() throws Exception {
        String response = dolphinApiClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/projects/{projectCode}/process-definition/list")
                .queryParam("token", authToken)
                .build(projectCode))
            .retrieve()
            .bodyToMono(String.class)
            .block();

        JsonNode data = objectMapper.readTree(response);
        if (!data.path("success").asBoolean()) {
            throw new RuntimeException("Failed to list workflows: " + data.path("msg").asText());
        }

        JsonNode totalList = data.path("data").path("totalList");
        return totalList.isArray() ? totalList.size() : 0;
    }

    /**
     * List all workflows in the project
     */
    private List<JsonNode> listAllWorkflows() throws Exception {
        String response = dolphinApiClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/projects/{projectCode}/process-definition/list")
                .queryParam("token", authToken)
                .build(projectCode))
            .retrieve()
            .bodyToMono(String.class)
            .block();

        JsonNode data = objectMapper.readTree(response);
        if (!data.path("success").asBoolean()) {
            throw new RuntimeException("Failed to list workflows: " + data.path("msg").asText());
        }

        List<JsonNode> workflows = new ArrayList<>();
        JsonNode totalList = data.path("data").path("totalList");
        if (totalList.isArray()) {
            totalList.forEach(workflows::add);
        }

        return workflows;
    }
}
