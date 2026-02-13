package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行态反向同步记录
 */
@Data
@TableName("workflow_runtime_sync_record")
public class WorkflowRuntimeSyncRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private String snapshotHash;

    private String snapshotJson;

    private String diffJson;

    /**
     * 成功同步后关联的版本ID
     */
    private Long versionId;

    /**
     * success / failed
     */
    private String status;

    private String errorCode;

    private String errorMessage;

    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
