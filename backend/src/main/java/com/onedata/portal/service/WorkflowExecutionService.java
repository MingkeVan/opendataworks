package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 工作流运行触发服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionService {

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final DolphinSchedulerService dolphinSchedulerService;

    public String executeWorkflow(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再执行");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String executionId = dolphinSchedulerService.startProcessInstance(
                    workflow.getDolphinConfigId(),
                    workflowCode,
                    null,
                    workflow.getWorkflowName());
            if (executionLog != null) {
                executionLog.setExecutionId(executionId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return executionId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (request == null) {
            throw new IllegalArgumentException("补数参数不能为空");
        }

        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再补数");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String triggerId = dolphinSchedulerService.backfillProcessInstance(
                    workflow.getDolphinConfigId(), workflowCode, request);
            if (executionLog != null) {
                executionLog.setExecutionId(triggerId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return triggerId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    private TaskExecutionLog createWorkflowExecutionLog(Long workflowId, String triggerType) {
        Long taskId = resolveMonitorTaskId(workflowId);
        if (taskId == null) {
            log.warn("No task relation found for workflow {}, skip execution log creation", workflowId);
            return null;
        }
        TaskExecutionLog logRecord = new TaskExecutionLog();
        logRecord.setTaskId(taskId);
        logRecord.setStatus("pending");
        logRecord.setStartTime(LocalDateTime.now());
        logRecord.setTriggerType(StringUtils.hasText(triggerType) ? triggerType : "manual");
        taskExecutionLogMapper.insert(logRecord);
        return logRecord;
    }

    private void markExecutionFailed(TaskExecutionLog executionLog, RuntimeException ex) {
        if (executionLog == null) {
            return;
        }
        executionLog.setStatus("failed");
        executionLog.setEndTime(LocalDateTime.now());
        executionLog.setErrorMessage(ex.getMessage());
        taskExecutionLogMapper.updateById(executionLog);
    }

    private Long resolveMonitorTaskId(Long workflowId) {
        if (workflowId == null) {
            return null;
        }
        WorkflowTaskRelation relation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getIsEntry)
                        .orderByAsc(WorkflowTaskRelation::getId)
                        .last("LIMIT 1"));
        return relation != null ? relation.getTaskId() : null;
    }
}
