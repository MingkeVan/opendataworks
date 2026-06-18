package com.onedata.portal.service;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataTableMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for task execution workflow lifecycle:
 * - Create tasks with the minimum required lineage metadata
 * - Verify direct execution rejects unpublished tasks
 * - Verify no Dolphin runtime call is required for unpublished task execution
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskExecutionWorkflowTest {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionWorkflowTest.class);

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DataTableMapper dataTableMapper;

    private final String testRunId = Long.toString(System.nanoTime());
    private List<Long> createdTaskIds = new ArrayList<>();
    private List<Long> createdTableIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        log.info("Cleaning up test data...");

        // Clean up test tasks
        for (Long taskId : createdTaskIds) {
            try {
                dataTaskService.delete(taskId);
                log.info("Deleted test task: {}", taskId);
            } catch (Exception e) {
                log.warn("Failed to delete task {}: {}", taskId, e.getMessage());
            }
        }

        for (Long tableId : createdTableIds) {
            try {
                dataTableMapper.deleteById(tableId);
                log.info("Deleted test table: {}", tableId);
            } catch (Exception e) {
                log.warn("Failed to delete table {}: {}", tableId, e.getMessage());
            }
        }

        log.info("Cleanup complete");
    }

    @Test
    void executeTaskShouldRejectUnpublishedTaskWithoutCreatingTempWorkflow() throws Exception {
        log.info("Starting test: executeTaskShouldRejectUnpublishedTaskWithoutCreatingTempWorkflow");

        // 1. Create a test task
        DataTask task = new DataTask();
        task.setTaskName("Test Task for Workflow Lifecycle " + testRunId);
        task.setTaskCode("test-workflow-" + System.currentTimeMillis());
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SHELL");
        task.setTaskSql("echo 'Test execution'");
        task.setStatus("draft");

        Long outputTableId = createOutputTable("single");
        dataTaskService.create(task, Collections.emptyList(), Collections.singletonList(outputTableId));
        createdTaskIds.add(task.getId());
        log.info("Created test task: id={}, code={}", task.getId(), task.getTaskCode());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> dataTaskService.executeTask(task.getId()));
        assertTrue(exception.getMessage().contains("任务未发布到工作流"),
                "Unpublished tasks should fail before Dolphin workflow execution");
    }

    @Test
    void multipleUnpublishedTaskExecutionsShouldNotCreateTempWorkflows() throws Exception {
        log.info("Starting test: multipleUnpublishedTaskExecutionsShouldNotCreateTempWorkflows");

        // Create multiple tasks
        List<DataTask> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DataTask task = new DataTask();
            task.setTaskName("Test Task " + testRunId + "-" + i);
            task.setTaskCode("test-multi-" + System.currentTimeMillis() + "-" + i);
            task.setTaskType("batch");
            task.setEngine("dolphin");
            task.setDolphinNodeType("SHELL");
            task.setTaskSql("echo 'Test " + i + "'");
            task.setStatus("draft");
            Long outputTableId = createOutputTable("multi-" + i);
            dataTaskService.create(task, Collections.emptyList(), Collections.singletonList(outputTableId));
            tasks.add(task);
            createdTaskIds.add(task.getId());
        }

        // Execute all tasks
        for (DataTask task : tasks) {
            log.info("Executing task: {}", task.getTaskName());
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> dataTaskService.executeTask(task.getId()));
            assertTrue(exception.getMessage().contains("任务未发布到工作流"),
                    "Unpublished tasks should fail before Dolphin workflow execution");
        }
    }

    private Long createOutputTable(String token) {
        DataTable table = new DataTable();
        table.setTableName("it_task_exec_out_" + token + "_" + System.nanoTime());
        table.setDbName("test_db");
        table.setLayer("dwd");
        table.setTableComment("task-execution-workflow-test");
        table.setOwner("it-task-execution");
        table.setStatus("active");
        dataTableMapper.insert(table);
        createdTableIds.add(table.getId());
        return table.getId();
    }
}
