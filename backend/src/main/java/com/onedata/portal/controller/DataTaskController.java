package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.dto.TaskExecutionStatus;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.service.DataTaskService;
import com.onedata.portal.service.DolphinSchedulerService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 任务管理 Controller
 */
@RestController
@RequestMapping("/v1/tasks")
@RequiredArgsConstructor
public class DataTaskController {

    private final DataTaskService dataTaskService;
    private final DolphinSchedulerService dolphinSchedulerService;

    /**
     * 分页查询任务列表
     */
    @GetMapping
    public Result<PageResult<DataTask>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) Long upstreamTaskId,
            @RequestParam(required = false) Long downstreamTaskId) {
        Page<DataTask> page = dataTaskService.list(pageNum, pageSize, taskType, status,
                workflowId, upstreamTaskId, downstreamTaskId);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    /**
     * 根据ID获取任务详情
     */
    @GetMapping("/{id}")
    public Result<DataTask> getById(@PathVariable Long id) {
        return Result.success(dataTaskService.getById(id));
    }

    /**
     * 创建任务
     */
    @PostMapping
    public Result<DataTask> create(@RequestBody TaskCreateRequest request) {
        DataTask task = dataTaskService.create(
                request.getTask(),
                request.getInputTableIds(),
                request.getOutputTableIds());
        return Result.success(task);
    }

    /**
     * 更新任务
     */
    @PutMapping("/{id}")
    public Result<DataTask> update(@PathVariable Long id, @RequestBody TaskUpdateRequest request) {
        request.getTask().setId(id);
        DataTask updatedTask = dataTaskService.update(
                request.getTask(),
                request.getInputTableIds(),
                request.getOutputTableIds());
        return Result.success(updatedTask);
    }

    /**
     * 执行整个工作流（包含所有依赖关系）
     */
    @PostMapping("/{id}/execute-workflow")
    public Result<Void> executeWorkflow(@PathVariable Long id) {
        dataTaskService.executeWorkflow(id);
        return Result.success();
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataTaskService.delete(id);
        return Result.success();
    }

    /**
     * 获取任务最近一次执行状态
     */
    @GetMapping("/{id}/execution-status")
    public Result<TaskExecutionStatus> getExecutionStatus(@PathVariable Long id) {
        TaskExecutionStatus status = dataTaskService.getLatestExecutionStatus(id);
        return Result.success(status);
    }

    /**
     * 获取 DolphinScheduler Web UI 配置
     */
    @GetMapping("/config/dolphin-webui")
    public Result<Map<String, String>> getDolphinWebuiConfig() {
        String webuiUrl = dolphinSchedulerService.getWebuiBaseUrl();
        return Result.success(Collections.singletonMap("webuiUrl", webuiUrl));
    }

    /**
     * 获取任务的血缘关系（输入表和输出表）
     */
    @GetMapping("/{id}/lineage")
    public Result<TaskLineageResponse> getTaskLineage(@PathVariable Long id) {
        TaskLineageResponse lineage = dataTaskService.getTaskLineage(id);
        return Result.success(lineage);
    }

    /**
     * 任务创建请求
     */
    @Data
    public static class TaskCreateRequest {
        private DataTask task;
        private List<Long> inputTableIds;
        private List<Long> outputTableIds;
    }

    /**
     * 任务更新请求
     */
    @Data
    public static class TaskUpdateRequest {
        private DataTask task;
        private List<Long> inputTableIds;
        private List<Long> outputTableIds;
    }

    /**
     * 任务血缘关系响应
     */
    @Data
    @AllArgsConstructor
    public static class TaskLineageResponse {
        private List<Long> inputTableIds;
        private List<Long> outputTableIds;
    }
}
