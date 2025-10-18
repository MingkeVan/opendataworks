package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务执行监控服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TaskExecutionService.class);

    private final TaskExecutionLogMapper executionLogMapper;
    private final DolphinSchedulerService dolphinSchedulerService;

    /**
     * 查询任务执行历史
     */
    public IPage<TaskExecutionLog> getExecutionHistory(Long taskId, Integer pageNum, Integer pageSize) {
        Page<TaskExecutionLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();

        if (taskId != null) {
            wrapper.eq(TaskExecutionLog::getTaskId, taskId);
        }

        wrapper.orderByDesc(TaskExecutionLog::getStartTime);

        return executionLogMapper.selectPage(page, wrapper);
    }

    /**
     * 获取单个执行记录详情
     */
    public TaskExecutionLog getExecutionDetail(Long executionLogId) {
        return executionLogMapper.selectById(executionLogId);
    }

    /**
     * 查询最近的执行记录
     */
    public List<TaskExecutionLog> getRecentExecutions(Long taskId, Integer limit) {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getTaskId, taskId)
                .orderByDesc(TaskExecutionLog::getStartTime)
                .last("LIMIT " + limit);

        return executionLogMapper.selectList(wrapper);
    }

    /**
     * 同步执行状态 - 从 DolphinScheduler 获取最新状态
     */
    public TaskExecutionLog syncExecutionStatus(Long executionLogId) {
        TaskExecutionLog execLog = executionLogMapper.selectById(executionLogId);
        if (execLog == null) {
            throw new IllegalArgumentException("Execution log not found: " + executionLogId);
        }

        // 如果已经是终态,不再同步
        if (isTerminalStatus(execLog.getStatus())) {
            return execLog;
        }

        try {
            // 从 DolphinScheduler 获取实例状态
            JsonNode instanceStatus = dolphinSchedulerService.getWorkflowInstanceStatus(
                execLog.getTaskId(), // 使用 taskCode 作为 workflowCode
                execLog.getExecutionId()
            );

            if (instanceStatus != null) {
                updateExecutionLog(execLog, instanceStatus);
                executionLogMapper.updateById(execLog);
            }
        } catch (Exception e) {
            // Use the class logger, not the entity variable
            logger.warn("Failed to sync execution status for log {}: {}", executionLogId, e.getMessage());
        }

        return execLog;
    }

    /**
     * 获取任务执行统计
     */
    public Map<String, Object> getExecutionStatistics(Long taskId, LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(taskId != null, TaskExecutionLog::getTaskId, taskId)
                .ge(startTime != null, TaskExecutionLog::getStartTime, startTime)
                .le(endTime != null, TaskExecutionLog::getEndTime, endTime);

        List<TaskExecutionLog> logs = executionLogMapper.selectList(wrapper);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalExecutions", logs.size());

        if (logs.isEmpty()) {
            stats.put("successRate", 0.0);
            stats.put("failureRate", 0.0);
            stats.put("avgDurationSeconds", 0.0);
            stats.put("maxDurationSeconds", 0);
            stats.put("minDurationSeconds", 0);
            stats.put("statusDistribution", new HashMap<String, Long>());
            return stats;
        }

        // 按状态分组统计
        Map<String, Long> statusDistribution = logs.stream()
                .collect(Collectors.groupingBy(
                        TaskExecutionLog::getStatus,
                        Collectors.counting()
                ));
        stats.put("statusDistribution", statusDistribution);

        // 成功率和失败率
        long successCount = statusDistribution.getOrDefault("success", 0L);
        long failedCount = statusDistribution.getOrDefault("failed", 0L);
        double successRate = (double) successCount / logs.size() * 100;
        double failureRate = (double) failedCount / logs.size() * 100;
        stats.put("successRate", Math.round(successRate * 100.0) / 100.0);
        stats.put("failureRate", Math.round(failureRate * 100.0) / 100.0);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);

        // 执行时长统计
        List<Integer> durations = logs.stream()
                .filter(log -> log.getDurationSeconds() != null && log.getDurationSeconds() > 0)
                .map(TaskExecutionLog::getDurationSeconds)
                .collect(Collectors.toList());

        if (!durations.isEmpty()) {
            double avgDuration = durations.stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0);
            int maxDuration = durations.stream().mapToInt(Integer::intValue).max().orElse(0);
            int minDuration = durations.stream().mapToInt(Integer::intValue).min().orElse(0);

            stats.put("avgDurationSeconds", Math.round(avgDuration * 100.0) / 100.0);
            stats.put("maxDurationSeconds", maxDuration);
            stats.put("minDurationSeconds", minDuration);
        } else {
            stats.put("avgDurationSeconds", 0.0);
            stats.put("maxDurationSeconds", 0);
            stats.put("minDurationSeconds", 0);
        }

        // 最近7天的执行趋势
        if (startTime == null) {
            startTime = LocalDateTime.now().minusDays(7);
        }
        List<Map<String, Object>> trend = calculateExecutionTrend(logs, startTime);
        stats.put("executionTrend", trend);

        return stats;
    }

    /**
     * 获取失败任务列表
     */
    public List<TaskExecutionLog> getFailedExecutions(Integer limit) {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getStatus, "failed")
                .orderByDesc(TaskExecutionLog::getStartTime)
                .last(limit != null ? "LIMIT " + limit : "LIMIT 50");

        return executionLogMapper.selectList(wrapper);
    }

    /**
     * 获取正在运行的任务
     */
    public List<TaskExecutionLog> getRunningExecutions() {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getStatus, "running")
                .orderByDesc(TaskExecutionLog::getStartTime);

        return executionLogMapper.selectList(wrapper);
    }

    /**
     * 创建执行日志记录
     */
    public TaskExecutionLog createExecutionLog(Long taskId, String executionId, String triggerType) {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setTaskId(taskId);
        log.setExecutionId(executionId);
        log.setStatus("pending");
        log.setStartTime(LocalDateTime.now());
        log.setTriggerType(triggerType != null ? triggerType : "manual");

        executionLogMapper.insert(log);
        return log;
    }

    /**
     * 更新执行日志
     */
    private void updateExecutionLog(TaskExecutionLog log, JsonNode instanceStatus) {
        // 解析 DolphinScheduler 返回的状态
        String state = instanceStatus.path("state").asText();
        log.setStatus(mapDolphinStatusToOurs(state));

        // 更新时间信息
        String startTimeStr = instanceStatus.path("startTime").asText();
        String endTimeStr = instanceStatus.path("endTime").asText();

        if (startTimeStr != null && !startTimeStr.isEmpty()) {
            // 根据实际的时间格式解析
            // log.setStartTime(parseDateTime(startTimeStr));
        }

        if (endTimeStr != null && !endTimeStr.isEmpty() && !"null".equals(endTimeStr)) {
            // log.setEndTime(parseDateTime(endTimeStr));

            // 计算执行时长
            if (log.getStartTime() != null && log.getEndTime() != null) {
                Duration duration = Duration.between(log.getStartTime(), log.getEndTime());
                log.setDurationSeconds((int) duration.getSeconds());
            }
        }
    }

    /**
     * 映射 DolphinScheduler 状态到我们的状态
     */
    private String mapDolphinStatusToOurs(String dolphinStatus) {
        if (dolphinStatus == null) {
            return "pending";
        }

        switch (dolphinStatus.toUpperCase()) {
            case "SUCCESS":
                return "success";
            case "FAILURE":
            case "FAILED":
                return "failed";
            case "RUNNING_EXECUTION":
            case "RUNNING":
                return "running";
            case "STOP":
            case "KILL":
                return "killed";
            case "READY_PAUSE":
            case "PAUSE":
                return "paused";
            default:
                return "pending";
        }
    }

    /**
     * 判断是否为终态
     */
    private boolean isTerminalStatus(String status) {
        return "success".equals(status) || "failed".equals(status) || "killed".equals(status);
    }

    /**
     * 计算执行趋势
     */
    private List<Map<String, Object>> calculateExecutionTrend(List<TaskExecutionLog> logs, LocalDateTime startTime) {
        // 按日期分组统计
        Map<String, List<TaskExecutionLog>> logsByDate = logs.stream()
                .filter(log -> log.getStartTime() != null)
                .collect(Collectors.groupingBy(log ->
                    log.getStartTime().toLocalDate().toString()
                ));

        return logsByDate.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> dayStats = new HashMap<>();
                    dayStats.put("date", entry.getKey());
                    dayStats.put("total", entry.getValue().size());
                    dayStats.put("success", entry.getValue().stream()
                            .filter(log -> "success".equals(log.getStatus()))
                            .count());
                    dayStats.put("failed", entry.getValue().stream()
                            .filter(log -> "failed".equals(log.getStatus()))
                            .count());
                    return dayStats;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("date")))
                .collect(Collectors.toList());
    }
}
