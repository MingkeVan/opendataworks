package com.onedata.portal.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.service.TaskExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务执行监控控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/executions")
@RequiredArgsConstructor
public class TaskExecutionController {

    private final TaskExecutionService executionService;

    /**
     * 查询任务执行历史 (分页)
     *
     * @param taskId   任务ID (可选)
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 执行历史分页数据
     */
    @GetMapping("/history")
    public Result<Map<String, Object>> getExecutionHistory(
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("Query execution history: taskId={}, pageNum={}, pageSize={}", taskId, pageNum, pageSize);

        IPage<TaskExecutionLog> page = executionService.getExecutionHistory(taskId, pageNum, pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("pageNum", page.getCurrent());
        result.put("pageSize", page.getSize());
        result.put("records", page.getRecords());

        return Result.success(result);
    }

    /**
     * 获取单个执行记录详情
     *
     * @param id 执行记录ID
     * @return 执行记录详情
     */
    @GetMapping("/{id}")
    public Result<TaskExecutionLog> getExecutionDetail(@PathVariable Long id) {
        log.info("Query execution detail: id={}", id);
        return Result.success(executionService.getExecutionDetail(id));
    }

    /**
     * 获取任务的最近执行记录
     *
     * @param taskId 任务ID
     * @param limit  返回条数
     * @return 最近执行记录列表
     */
    @GetMapping("/recent")
    public Result<List<TaskExecutionLog>> getRecentExecutions(
            @RequestParam Long taskId,
            @RequestParam(defaultValue = "10") Integer limit) {
        log.info("Query recent executions: taskId={}, limit={}", taskId, limit);
        return Result.success(executionService.getRecentExecutions(taskId, limit));
    }

    /**
     * 同步执行状态 - 从 DolphinScheduler 获取最新状态
     *
     * @param id 执行记录ID
     * @return 更新后的执行记录
     */
    @PostMapping("/{id}/sync")
    public Result<TaskExecutionLog> syncExecutionStatus(@PathVariable Long id) {
        log.info("Sync execution status: id={}", id);
        return Result.success(executionService.syncExecutionStatus(id));
    }

    /**
     * 获取任务执行统计信息
     *
     * @param taskId    任务ID (可选)
     * @param startTime 开始时间 (可选)
     * @param endTime   结束时间 (可选)
     * @return 统计信息
     */
    @GetMapping("/statistics")
    public Result<Map<String, Object>> getExecutionStatistics(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        log.info("Query execution statistics: taskId={}, startTime={}, endTime={}", taskId, startTime, endTime);
        return Result.success(executionService.getExecutionStatistics(taskId, startTime, endTime));
    }

    /**
     * 获取失败任务列表
     *
     * @param limit 返回条数
     * @return 失败任务列表
     */
    @GetMapping("/failed")
    public Result<List<TaskExecutionLog>> getFailedExecutions(
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        log.info("Query failed executions: limit={}", limit);
        return Result.success(executionService.getFailedExecutions(limit));
    }

    /**
     * 获取正在运行的任务
     *
     * @return 运行中任务列表
     */
    @GetMapping("/running")
    public Result<List<TaskExecutionLog>> getRunningExecutions() {
        log.info("Query running executions");
        return Result.success(executionService.getRunningExecutions());
    }

    /**
     * 创建执行日志记录
     *
     * @param request 请求参数
     * @return 创建的执行记录
     */
    @PostMapping
    public Result<TaskExecutionLog> createExecutionLog(@RequestBody CreateExecutionLogRequest request) {
        log.info("Create execution log: taskId={}, executionId={}, triggerType={}",
                request.getTaskId(), request.getExecutionId(), request.getTriggerType());

        return Result.success(executionService.createExecutionLog(
                request.getTaskId(),
                request.getExecutionId(),
                request.getTriggerType()
        ));
    }

    /**
     * 创建执行日志请求
     */
    @lombok.Data
    public static class CreateExecutionLogRequest {
        private Long taskId;
        private String executionId;
        private String triggerType; // manual, schedule, api
    }
}
