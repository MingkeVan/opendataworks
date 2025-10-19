package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 任务执行日志实体
 */
@Data
@TableName("task_execution_log")
public class TaskExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String executionId;

    private String status; // pending, running, success, failed, killed

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private Long rowsOutput;

    private String errorMessage;

    private String logUrl;

    private String triggerType; // manual, schedule, api

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // DolphinScheduler 相关字段 (不存储到数据库,仅用于API响应)
    @TableField(exist = false)
    private Long workflowCode;

    @TableField(exist = false)
    private String workflowName;

    @TableField(exist = false)
    private String workflowInstanceUrl; // DolphinScheduler WebUI 工作流实例链接

    @TableField(exist = false)
    private String taskDefinitionUrl; // DolphinScheduler WebUI 任务定义链接

    @TableField(exist = false)
    private Object dolphinInstanceDetail; // DolphinScheduler 实例详情 (来自实时查询)
}
