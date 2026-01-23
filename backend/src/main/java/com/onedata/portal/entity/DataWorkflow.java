package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台侧工作流定义
 */
@Data
@TableName("data_workflow")
public class DataWorkflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowCode;

    private Long projectCode;

    private String workflowName;

    private String status;

    private String publishStatus;

    private Long currentVersionId;

    private Long lastPublishedVersionId;

    private String definitionJson;

    private String entryTaskIds;

    private String exitTaskIds;

    private String description;

    private String createdBy;

    private String updatedBy;

    /**
     * 全局参数 (JSON format: [{"prop": "key", "value": "val", ...}])
     */
    private String globalParams;

    /**
     * 默认任务组（DolphinScheduler Task Group）
     */
    private String taskGroupName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private Long latestInstanceId;

    @TableField(exist = false)
    private String latestInstanceState;

    @TableField(exist = false)
    private LocalDateTime latestInstanceStartTime;

    @TableField(exist = false)
    private LocalDateTime latestInstanceEndTime;
}
