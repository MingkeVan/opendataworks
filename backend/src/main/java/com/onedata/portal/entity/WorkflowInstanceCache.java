package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 最近执行历史缓存
 */
@Data
@TableName("workflow_instance_cache")
public class WorkflowInstanceCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long instanceId;

    private String state;

    private Date startTime;

    private Date endTime;

    private String triggerType;

    private Long durationMs;

    private String extra;

    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
}
