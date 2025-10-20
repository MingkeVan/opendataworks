package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataTaskMapper;
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
    private final DataTaskMapper dataTaskMapper;
    private final DolphinSchedulerService dolphinSchedulerService;

    /**
     * 查询任务执行历史 - 从 DolphinScheduler 实时同步状态
     */
    public IPage<TaskExecutionLog> getExecutionHistory(Long taskId, Integer pageNum, Integer pageSize) {
        Page<TaskExecutionLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();

        if (taskId != null) {
            wrapper.eq(TaskExecutionLog::getTaskId, taskId);
        }

        wrapper.orderByDesc(TaskExecutionLog::getStartTime);

        IPage<TaskExecutionLog> result = executionLogMapper.selectPage(page, wrapper);

        // 丰富每条记录的 DolphinScheduler 信息
        result.getRecords().forEach(this::enrichWithDolphinData);

        return result;
    }

    /**
     * 获取单个执行记录详情 - 以 DolphinScheduler 数据为准
     */
    public TaskExecutionLog getExecutionDetail(Long executionLogId) {
        TaskExecutionLog log = executionLogMapper.selectById(executionLogId);
        if (log != null) {
            enrichWithDolphinData(log);
        }
        return log;
    }

    /**
     * 查询最近的执行记录 - 包含 DolphinScheduler 实时状态
     */
    public List<TaskExecutionLog> getRecentExecutions(Long taskId, Integer limit) {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getTaskId, taskId)
                .orderByDesc(TaskExecutionLog::getStartTime)
                .last("LIMIT " + limit);

        List<TaskExecutionLog> logs = executionLogMapper.selectList(wrapper);
        logs.forEach(this::enrichWithDolphinData);
        return logs;
    }

    /**
     * 丰富执行日志数据 - 添加 DolphinScheduler 实时信息和 WebUI 链接
     * 以 DolphinScheduler 为权威数据源
     */
    private void enrichWithDolphinData(TaskExecutionLog log) {
        try {
            // 1. 获取任务信息以获取 workflow code
            DataTask task = dataTaskMapper.selectById(log.getTaskId());
            if (task != null) {
                Long workflowCode = task.getDolphinProcessCode();
                Long taskCode = task.getDolphinTaskCode();

                log.setWorkflowCode(workflowCode);
                log.setWorkflowName(dolphinSchedulerService.getWorkflowName());
                log.setTaskCode(taskCode);

                // 2. 生成 DolphinScheduler WebUI 链接
                if (taskCode != null) {
                    String taskUrl = dolphinSchedulerService.getTaskDefinitionUrl(taskCode);
                    log.setTaskDefinitionUrl(taskUrl);
                }

                // 3. 从 DolphinScheduler 获取实时实例状态 (作为权威数据源)
                if (workflowCode != null && log.getExecutionId() != null) {
                    JsonNode instanceDetail = dolphinSchedulerService.getWorkflowInstanceStatus(
                        workflowCode,
                        log.getExecutionId()
                    );

                    if (instanceDetail != null) {
                        // 保存完整的 Dolphin 实例详情
                        log.setDolphinInstanceDetail(instanceDetail);

                        // 提取并设置 DolphinScheduler 实例信息到直接字段
                        if (instanceDetail.has("instanceId")) {
                            log.setDolphinInstanceId(instanceDetail.path("instanceId").asInt());
                        }

                        String dolphinState = instanceDetail.path("state").asText();
                        log.setDolphinState(dolphinState);

                        if (instanceDetail.has("startTime") && !instanceDetail.path("startTime").isNull()) {
                            log.setDolphinStartTime(instanceDetail.path("startTime").asText());
                        }

                        if (instanceDetail.has("endTime") && !instanceDetail.path("endTime").isNull()) {
                            log.setDolphinEndTime(instanceDetail.path("endTime").asText());
                        }

                        if (instanceDetail.has("duration") && !instanceDetail.path("duration").isNull()) {
                            log.setDolphinDuration(instanceDetail.path("duration").asInt());
                        }

                        if (instanceDetail.has("runTimes")) {
                            log.setRunTimes(instanceDetail.path("runTimes").asInt());
                        }

                        if (instanceDetail.has("host") && !instanceDetail.path("host").isNull()) {
                            log.setHost(instanceDetail.path("host").asText());
                        }

                        if (instanceDetail.has("commandType") && !instanceDetail.path("commandType").isNull()) {
                            log.setCommandType(instanceDetail.path("commandType").asText());
                        }

                        // 4. 使用 DolphinScheduler 的状态更新本地记录 (Dolphin 为准)
                        String mappedStatus = mapDolphinStatusToOurs(dolphinState);

                        // 如果 Dolphin 状态与本地不一致,以 Dolphin 为准并更新本地
                        if (!mappedStatus.equals(log.getStatus()) && !isTerminalStatus(log.getStatus())) {
                            log.setStatus(mappedStatus);
                            updateExecutionLogFromDolphin(log, instanceDetail);
                            executionLogMapper.updateById(log);
                            logger.info("Updated execution log {} status from Dolphin: {} -> {}",
                                log.getId(), log.getStatus(), mappedStatus);
                        }

                        // 5. 生成工作流实例 WebUI 链接
                        Long projectCode = dolphinSchedulerService.getProjectCode();
                        if (projectCode != null && workflowCode != null) {
                            String workflowDefUrl = dolphinSchedulerService.getWorkflowDefinitionUrl(workflowCode);
                            if (workflowDefUrl != null) {
                                String baseUrl = workflowDefUrl.replaceAll("/workflow/definition/.*$", "");
                                String instanceUrl = String.format(
                                    "%s/workflow/instance/%d/%s",
                                    baseUrl, workflowCode, log.getExecutionId()
                                );
                                log.setWorkflowInstanceUrl(instanceUrl);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to enrich execution log {} with Dolphin data: {}",
                log.getId(), e.getMessage());
        }
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
            // Get the workflow code from the DataTask, not from the execution log's taskId
            DataTask task = dataTaskMapper.selectById(execLog.getTaskId());
            if (task == null || task.getDolphinProcessCode() == null) {
                logger.warn("Cannot sync execution status: DataTask {} not found or missing DolphinProcessCode", execLog.getTaskId());
                return execLog;
            }

            // 从 DolphinScheduler 获取实例状态
            JsonNode instanceStatus = dolphinSchedulerService.getWorkflowInstanceStatus(
                task.getDolphinProcessCode(), // Use the correct workflow code from DataTask
                execLog.getExecutionId()
            );

            if (instanceStatus != null) {
                updateExecutionLogFromDolphin(execLog, instanceStatus);
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
     * 获取失败任务列表 - 包含 DolphinScheduler 实时状态
     */
    public List<TaskExecutionLog> getFailedExecutions(Integer limit) {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getStatus, "failed")
                .orderByDesc(TaskExecutionLog::getStartTime)
                .last(limit != null ? "LIMIT " + limit : "LIMIT 50");

        List<TaskExecutionLog> logs = executionLogMapper.selectList(wrapper);
        logs.forEach(this::enrichWithDolphinData);
        return logs;
    }

    /**
     * 获取正在运行的任务 - 包含 DolphinScheduler 实时状态
     */
    public List<TaskExecutionLog> getRunningExecutions() {
        LambdaQueryWrapper<TaskExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getStatus, "running")
                .orderByDesc(TaskExecutionLog::getStartTime);

        List<TaskExecutionLog> logs = executionLogMapper.selectList(wrapper);
        logs.forEach(this::enrichWithDolphinData);
        return logs;
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
     * 更新执行日志 - 从 DolphinScheduler 实例信息
     */
    private void updateExecutionLogFromDolphin(TaskExecutionLog log, JsonNode instanceStatus) {
        // 解析 DolphinScheduler 返回的状态
        String state = instanceStatus.path("state").asText();
        log.setStatus(mapDolphinStatusToOurs(state));

        // 更新时间信息 - 从 DolphinScheduler 同步时间
        String startTimeStr = instanceStatus.path("startTime").asText();
        String endTimeStr = instanceStatus.path("endTime").asText();

        if (startTimeStr != null && !startTimeStr.isEmpty() && !"null".equals(startTimeStr)) {
            try {
                // DolphinScheduler 返回的时间格式通常为 "yyyy-MM-dd HH:mm:ss"
                LocalDateTime startTime = LocalDateTime.parse(
                    startTimeStr.replace(" ", "T")  // 转换为 ISO 格式
                );
                log.setStartTime(startTime);
            } catch (Exception e) {
                logger.warn("Failed to parse start time from Dolphin: {}", startTimeStr);
            }
        }

        if (endTimeStr != null && !endTimeStr.isEmpty() && !"null".equals(endTimeStr)) {
            try {
                LocalDateTime endTime = LocalDateTime.parse(
                    endTimeStr.replace(" ", "T")  // 转换为 ISO 格式
                );
                log.setEndTime(endTime);
            } catch (Exception e) {
                logger.warn("Failed to parse end time from Dolphin: {}", endTimeStr);
            }
        }

        // 计算执行时长
        if (log.getStartTime() != null && log.getEndTime() != null) {
            Duration duration = Duration.between(log.getStartTime(), log.getEndTime());
            log.setDurationSeconds((int) duration.getSeconds());
        }

        // 如果 DolphinScheduler 返回了 duration 字段,优先使用它
        if (instanceStatus.has("duration") && !instanceStatus.path("duration").isNull()) {
            log.setDurationSeconds(instanceStatus.path("duration").asInt());
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
