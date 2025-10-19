package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.onedata.portal.dto.TaskExecutionStatus;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTaskService {

    private final DataTaskMapper dataTaskMapper;
    private final DataLineageMapper dataLineageMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTableService dataTableService;

    /**
     * 分页查询任务列表
     */
    public Page<DataTask> list(int pageNum, int pageSize, String taskType, String status) {
        Page<DataTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTask> wrapper = new LambdaQueryWrapper<>();

        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(DataTask::getTaskType, taskType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DataTask::getStatus, status);
        }

        wrapper.orderByDesc(DataTask::getCreatedAt);
        return dataTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 根据ID获取任务
     */
    public DataTask getById(Long id) {
        return dataTaskMapper.selectById(id);
    }

    /**
     * 创建任务
     */
    @Transactional
    public DataTask create(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        // 如果未提供任务编码,自动生成一个唯一编码
        String taskCode = task.getTaskCode();
        if (taskCode == null || taskCode.trim().isEmpty()) {
            task.setTaskCode(generateUniqueTaskCode(task.getTaskName()));
        }

        // 检查任务编码是否已存在
        DataTask exists = dataTaskMapper.selectOne(
            new LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getTaskCode, task.getTaskCode())
        );
        if (exists != null) {
            throw new RuntimeException("任务编码已存在: " + task.getTaskCode());
        }

        task.setStatus("draft");
        dataTaskMapper.insert(task);
        log.info("Created task: {}", task.getTaskName());

        // 创建血缘关系
        if (inputTableIds != null) {
            for (Long tableId : inputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setUpstreamTableId(tableId);
                lineage.setLineageType("input");
                dataLineageMapper.insert(lineage);
            }
        }

        if (outputTableIds != null) {
            for (Long tableId : outputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setDownstreamTableId(tableId);
                lineage.setLineageType("output");
                dataLineageMapper.insert(lineage);
            }
        }

        return task;
    }

    /**
     * 更新任务
     */
    @Transactional
    public DataTask update(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        DataTask exists = dataTaskMapper.selectById(task.getId());
        if (exists == null) {
            throw new RuntimeException("任务不存在");
        }

        // 更新任务基本信息
        dataTaskMapper.updateById(task);
        log.info("Updated task: {}", task.getTaskName());

        // 删除旧的血缘关系
        dataLineageMapper.delete(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, task.getId())
        );

        // 创建新的输入血缘关系
        if (inputTableIds != null) {
            for (Long tableId : inputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setUpstreamTableId(tableId);
                lineage.setLineageType("input");
                dataLineageMapper.insert(lineage);
            }
        }

        // 创建新的输出血缘关系
        if (outputTableIds != null) {
            for (Long tableId : outputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setDownstreamTableId(tableId);
                lineage.setLineageType("output");
                dataLineageMapper.insert(lineage);
            }
        }

        return task;
    }

    /**
     * 更新任务（仅基本信息，不更新血缘）
     * @deprecated 使用 update(DataTask, List<Long>, List<Long>) 代替
     */
    @Deprecated
    @Transactional
    public DataTask update(DataTask task) {
        return update(task, null, null);
    }

    /**
     * 发布任务到 DolphinScheduler。该操作会同步所有 Dolphin 引擎的任务定义，
     * 根据血缘信息自动建立上下游依赖。
     */
    @Transactional
    public void publish(Long taskId) {
        log.info("开始发布任务: taskId={}", taskId);

        DataTask target = dataTaskMapper.selectById(taskId);
        if (target == null) {
            log.error("任务不存在: taskId={}", taskId);
            throw new RuntimeException("任务不存在: ID=" + taskId);
        }
        if (!"dolphin".equalsIgnoreCase(target.getEngine())) {
            log.error("不支持的引擎类型: taskId={}, engine={}", taskId, target.getEngine());
            throw new RuntimeException("仅支持 Dolphin 引擎任务发布，当前引擎: " + target.getEngine());
        }

        log.info("任务信息: taskCode={}, taskName={}, engine={}",
            target.getTaskCode(), target.getTaskName(), target.getEngine());

        // 查询全部 Dolphin 任务，构建统一工作流
        log.info("查询所有 Dolphin 引擎任务...");
        List<DataTask> dolphinTasks = dataTaskMapper.selectList(
            new LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getEngine, "dolphin")
                .orderByAsc(DataTask::getId)
        );
        if (dolphinTasks.isEmpty()) {
            log.error("未找到任何 Dolphin 引擎任务");
            throw new RuntimeException("未找到任何 Dolphin 引擎任务");
        }
        log.info("找到 {} 个 Dolphin 引擎任务", dolphinTasks.size());

        // 获取已存在的 workflow code (如果有的话)
        Long existingWorkflowCode = dolphinTasks.stream()
            .map(DataTask::getDolphinProcessCode)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        long workflowCode = existingWorkflowCode != null ? existingWorkflowCode : 0L;

        // 对齐任务编号生成器，避免与已存在的任务编码冲突
        dolphinSchedulerService.alignSequenceWithExistingTasks(
            dolphinTasks.stream()
                .map(DataTask::getDolphinTaskCode)
                .collect(Collectors.toList())
        );

        for (DataTask dataTask : dolphinTasks) {
            boolean changed = false;
            if (dataTask.getDolphinTaskCode() == null) {
                dataTask.setDolphinTaskCode(dolphinSchedulerService.nextTaskCode());
                dataTask.setDolphinTaskVersion(1);
                changed = true;
            }
            if (dataTask.getDolphinTaskVersion() == null) {
                dataTask.setDolphinTaskVersion(1);
                changed = true;
            }
            // 暂时不更新 dolphinProcessCode，等 syncWorkflow 返回实际的 code
            if (changed) {
                dataTaskMapper.updateById(dataTask);
            }
        }

        Map<Long, DataTask> taskMap = dolphinTasks.stream()
            .collect(java.util.stream.Collectors.toMap(DataTask::getId, t -> t));

        List<Map<String, Object>> definitions = new ArrayList<>();
        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();

        int index = 0;
        for (DataTask dataTask : dolphinTasks) {
            String priority = mapPriority(dataTask.getPriority());
            int version = dataTask.getDolphinTaskVersion() != null ? dataTask.getDolphinTaskVersion() : 1;

            // Determine node type (default to SHELL if not specified)
            String nodeType = dataTask.getDolphinNodeType() != null ? dataTask.getDolphinNodeType() : "SHELL";
            String sqlOrScript;

            // For SQL node, use raw SQL; for SHELL, wrap in shell script
            if ("SQL".equalsIgnoreCase(nodeType)) {
                sqlOrScript = dataTask.getTaskSql();
            } else {
                sqlOrScript = dolphinSchedulerService.buildShellScript(dataTask.getTaskSql());
            }

            Map<String, Object> definition = dolphinSchedulerService.buildTaskDefinition(
                dataTask.getDolphinTaskCode(),
                version,
                dataTask.getTaskName(),
                dataTask.getTaskDesc(),
                sqlOrScript,
                priority,
                dataTask.getRetryTimes() == null ? 0 : dataTask.getRetryTimes(),
                dataTask.getRetryInterval() == null ? 1 : dataTask.getRetryInterval(),
                dataTask.getTimeoutSeconds() == null ? 0 : dataTask.getTimeoutSeconds(),
                nodeType,
                dataTask.getDatasourceName(),
                dataTask.getDatasourceType()
            );
            definitions.add(definition);

            List<DataTask> upstreamTasks = resolveUpstreamTasks(dataTask.getId(), taskMap);
            if (upstreamTasks.isEmpty()) {
                relations.add(dolphinSchedulerService.buildRelation(
                    0L, 0, dataTask.getDolphinTaskCode(), version
                ));
            } else {
                for (DataTask upstream : upstreamTasks) {
                    relations.add(dolphinSchedulerService.buildRelation(
                        upstream.getDolphinTaskCode(),
                        upstream.getDolphinTaskVersion() == null ? 1 : upstream.getDolphinTaskVersion(),
                        dataTask.getDolphinTaskCode(),
                        version
                    ));
                }
            }

            int lane = computeLaneByLayer(dataTask);
            locations.add(dolphinSchedulerService.buildLocation(
                dataTask.getDolphinTaskCode(),
                index++,
                lane
            ));
        }

        // syncWorkflow 返回实际的 workflow code (如果是新建则返回新的 code)
        log.info("开始同步工作流到 DolphinScheduler: workflowCode={}, taskCount={}",
            workflowCode, definitions.size());
        try {
            long actualWorkflowCode = dolphinSchedulerService.syncWorkflow(
                workflowCode,
                dolphinSchedulerService.getWorkflowName(),
                definitions,
                relations,
                locations
            );
            log.info("工作流同步成功: actualWorkflowCode={}", actualWorkflowCode);

            // 更新所有 dolphin 任务的 dolphinProcessCode
            log.info("更新任务的 workflow code...");
            for (DataTask dataTask : dolphinTasks) {
                if (!Objects.equals(dataTask.getDolphinProcessCode(), actualWorkflowCode)) {
                    dataTask.setDolphinProcessCode(actualWorkflowCode);
                    dataTaskMapper.updateById(dataTask);
                }
            }

            // Auto-release workflow to ONLINE state
            log.info("设置工作流状态为 ONLINE: workflowCode={}", actualWorkflowCode);
            dolphinSchedulerService.setWorkflowReleaseState(actualWorkflowCode, "ONLINE");

            target.setStatus("published");
            target.setDolphinProcessCode(actualWorkflowCode);
            dataTaskMapper.updateById(target);
            log.info("任务发布成功: taskId={}, taskName={}, workflowCode={}, status=ONLINE",
                taskId, target.getTaskName(), actualWorkflowCode);
        } catch (Exception e) {
            log.error("发布任务失败: taskId={}, taskName={}, error={}",
                taskId, target.getTaskName(), e.getMessage(), e);
            throw new RuntimeException("发布任务到 DolphinScheduler 失败: " + e.getMessage(), e);
        }
    }

    private List<DataTask> resolveUpstreamTasks(Long taskId, Map<Long, DataTask> taskMap) {
        List<DataLineage> inputLineage = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
                .eq(DataLineage::getLineageType, "input")
        );

        Set<Long> upstreamTableIds = inputLineage.stream()
            .map(DataLineage::getUpstreamTableId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (upstreamTableIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<DataLineage> outputLineage = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .in(DataLineage::getDownstreamTableId, upstreamTableIds)
                .eq(DataLineage::getLineageType, "output")
        );

        return outputLineage.stream()
            .map(DataLineage::getTaskId)
            .filter(Objects::nonNull)
            .map(taskMap::get)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
    }

    private String mapPriority(Integer value) {
        int priority = value == null ? 5 : value;
        if (priority >= 9) {
            return "HIGHEST";
        } else if (priority >= 7) {
            return "HIGH";
        } else if (priority >= 5) {
            return "MEDIUM";
        } else if (priority >= 3) {
            return "LOW";
        }
        return "LOWEST";
    }

    private int computeLaneByLayer(DataTask task) {
        String type = task.getTaskType() == null ? "batch" : task.getTaskType().toLowerCase();
        if ("stream".equals(type)) {
            return 1;
        }
        if ("dim".equals(type) || "dimension".equals(type)) {
            return 2;
        }
        return 0;
    }

    /**
     * 执行单个任务（测试模式）
     * 创建临时的单任务工作流，忽略上游依赖，用于测试验证
     */
    @Transactional
    public void executeTask(Long taskId) {
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        if (!"dolphin".equalsIgnoreCase(task.getEngine())) {
            throw new RuntimeException("仅支持 Dolphin 引擎任务执行");
        }

        // 确保任务有 task code 和 version
        if (task.getDolphinTaskCode() == null) {
            task.setDolphinTaskCode(dolphinSchedulerService.nextTaskCode());
            task.setDolphinTaskVersion(1);
            dataTaskMapper.updateById(task);
        }

        // 创建临时单任务工作流
        String tempWorkflowName = "test-task-" + task.getDolphinTaskCode();

        // 构建任务定义
        String priority = mapPriority(task.getPriority());
        int version = task.getDolphinTaskVersion() != null ? task.getDolphinTaskVersion() : 1;
        String nodeType = task.getDolphinNodeType() != null ? task.getDolphinNodeType() : "SHELL";
        String sqlOrScript;

        if ("SQL".equalsIgnoreCase(nodeType)) {
            sqlOrScript = task.getTaskSql();
        } else {
            sqlOrScript = dolphinSchedulerService.buildShellScript(task.getTaskSql());
        }

        Map<String, Object> definition = dolphinSchedulerService.buildTaskDefinition(
            task.getDolphinTaskCode(),
            version,
            task.getTaskName(),
            task.getTaskDesc(),
            sqlOrScript,
            priority,
            task.getRetryTimes() == null ? 0 : task.getRetryTimes(),
            task.getRetryInterval() == null ? 1 : task.getRetryInterval(),
            task.getTimeoutSeconds() == null ? 0 : task.getTimeoutSeconds(),
            nodeType,
            task.getDatasourceName(),
            null  // Don't pass datasourceType - let DolphinScheduler find datasource by name only
        );

        // 单任务工作流，无上游依赖
        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        relations.add(dolphinSchedulerService.buildRelation(0L, 0, task.getDolphinTaskCode(), version));

        // 任务位置
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();
        locations.add(dolphinSchedulerService.buildLocation(task.getDolphinTaskCode(), 0, 0));

        // 同步临时工作流（使用 0 表示创建新工作流）
        long tempWorkflowCode = dolphinSchedulerService.syncWorkflow(
            0L,
            tempWorkflowName,
            Collections.singletonList(definition),
            relations,
            locations
        );

        // 设置为 ONLINE 状态
        dolphinSchedulerService.setWorkflowReleaseState(tempWorkflowCode, "ONLINE");

        // 创建执行日志
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setStatus("pending");
        executionLog.setStartTime(LocalDateTime.now());
        executionLog.setTriggerType("manual");
        executionLogMapper.insert(executionLog);

        // 执行临时工作流
        String executionId = dolphinSchedulerService.startProcessInstance(tempWorkflowCode);
        executionLog.setExecutionId(executionId);
        executionLog.setStatus("running");
        executionLogMapper.updateById(executionLog);

        log.info("Started single task execution (test mode): task={} workflow={} execution={}",
            task.getTaskName(), tempWorkflowName, executionId);
    }

    /**
     * 执行整个工作流（原有逻辑）
     */
    @Transactional
    public void executeWorkflow(Long taskId) {
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        if (task.getDolphinProcessCode() == null) {
            throw new RuntimeException("任务未发布到工作流");
        }

        // 创建执行日志
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setStatus("pending");
        executionLog.setStartTime(LocalDateTime.now());
        executionLog.setTriggerType("manual");
        executionLogMapper.insert(executionLog);

        // 执行统一工作流
        String executionId = dolphinSchedulerService.startProcessInstance(task.getDolphinProcessCode());
        executionLog.setExecutionId(executionId);
        executionLog.setStatus("running");
        executionLogMapper.updateById(executionLog);

        log.info("Started workflow execution: task={} workflow={} execution={}",
            task.getTaskName(), task.getDolphinProcessCode(), executionId);
    }

    /**
     * @deprecated 使用 executeTask 或 executeWorkflow 代替
     */
    @Deprecated
    @Transactional
    public void execute(Long taskId) {
        executeWorkflow(taskId);
    }

    /**
     * 删除任务
     */
    @Transactional
    public void delete(Long id) {
        // 删除血缘关系
        dataLineageMapper.delete(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, id)
        );

        dataTaskMapper.deleteById(id);
        log.info("Deleted task: {}", id);
    }

    /**
     * 获取任务的最近一次执行状态
     */
    public TaskExecutionStatus getLatestExecutionStatus(Long taskId) {
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }

        // 获取最近一次执行记录
        TaskExecutionLog latestLog = executionLogMapper.selectOne(
            new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getTaskId, taskId)
                .orderByDesc(TaskExecutionLog::getCreatedAt)
                .last("LIMIT 1")
        );

        TaskExecutionStatus status = new TaskExecutionStatus();
        status.setTaskId(taskId);
        status.setDolphinWorkflowCode(task.getDolphinProcessCode());
        status.setDolphinTaskCode(task.getDolphinTaskCode());
        status.setDolphinProjectName(dolphinSchedulerService.getWorkflowName());

        if (latestLog != null) {
            status.setExecutionId(latestLog.getExecutionId());
            status.setStatus(latestLog.getStatus());
            status.setStartTime(latestLog.getStartTime());
            status.setEndTime(latestLog.getEndTime());
            status.setDurationSeconds(latestLog.getDurationSeconds());
            status.setErrorMessage(latestLog.getErrorMessage());
            status.setLogUrl(latestLog.getLogUrl());
            status.setTriggerType(latestLog.getTriggerType());

            // 如果有 workflow code 和 execution id，尝试从 DolphinScheduler 获取实时状态
            if (task.getDolphinProcessCode() != null && latestLog.getExecutionId() != null) {
                try {
                    JsonNode instanceData = dolphinSchedulerService.getWorkflowInstanceStatus(
                        task.getDolphinProcessCode(),
                        latestLog.getExecutionId()
                    );

                    if (instanceData != null) {
                        // 更新状态信息
                        String state = instanceData.path("state").asText(null);
                        if (state != null) {
                            status.setStatus(mapDolphinStateToStatus(state));
                        }

                        // 更新时间信息
                        String startTimeStr = instanceData.path("startTime").asText(null);
                        String endTimeStr = instanceData.path("endTime").asText(null);
                        if (startTimeStr != null && !startTimeStr.isEmpty()) {
                            // 时间格式转换根据实际情况调整
                            status.setStartTime(LocalDateTime.parse(startTimeStr));
                        }
                        if (endTimeStr != null && !endTimeStr.isEmpty()) {
                            status.setEndTime(LocalDateTime.parse(endTimeStr));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to get real-time status from DolphinScheduler for task {}: {}",
                        taskId, e.getMessage());
                }
            }
        }

        // 生成 DolphinScheduler Web UI 跳转链接
        if (task.getDolphinProcessCode() != null) {
            status.setDolphinWorkflowUrl(dolphinSchedulerService.getWorkflowDefinitionUrl(task.getDolphinProcessCode()));
        }
        if (task.getDolphinTaskCode() != null) {
            status.setDolphinTaskUrl(dolphinSchedulerService.getTaskDefinitionUrl(task.getDolphinTaskCode()));
        }

        return status;
    }

    /**
     * 将 DolphinScheduler 状态映射到本地状态
     */
    private String mapDolphinStateToStatus(String dolphinState) {
        if (dolphinState == null) {
            return "pending";
        }
        switch (dolphinState.toUpperCase()) {
            case "RUNNING_EXECUTION":
            case "SUBMITTED_SUCCESS":
                return "running";
            case "SUCCESS":
                return "success";
            case "FAILURE":
            case "FAILED":
                return "failed";
            case "STOP":
            case "KILL":
                return "killed";
            default:
                return "pending";
        }
    }

    /**
     * 生成唯一的任务编码
     * 规则: task_ + 时间戳 + 随机数
     */
    private String generateUniqueTaskCode(String taskName) {
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 1000);
        return String.format("task_%d_%03d", timestamp, random);
    }

    /**
     * 获取任务的血缘关系（输入表和输出表ID列表）
     */
    public com.onedata.portal.controller.DataTaskController.TaskLineageResponse getTaskLineage(Long taskId) {
        // 获取输入表
        List<DataLineage> inputLineages = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
                .eq(DataLineage::getLineageType, "input")
        );
        List<Long> inputTableIds = inputLineages.stream()
            .map(DataLineage::getUpstreamTableId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // 获取输出表
        List<DataLineage> outputLineages = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
                .eq(DataLineage::getLineageType, "output")
        );
        List<Long> outputTableIds = outputLineages.stream()
            .map(DataLineage::getDownstreamTableId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        return new com.onedata.portal.controller.DataTaskController.TaskLineageResponse(
            inputTableIds,
            outputTableIds
        );
    }
}
