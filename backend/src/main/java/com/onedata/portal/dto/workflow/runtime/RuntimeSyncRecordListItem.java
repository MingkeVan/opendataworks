package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步记录列表项
 */
@Data
public class RuntimeSyncRecordListItem {

    private Long id;

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private Long versionId;

    private String status;

    /**
     * 运行态定义采集模式（当前固定为 export_only）
     */
    private String ingestMode;

    /**
     * 一致性状态: not_checked/consistent/inconsistent
     */
    private String parityStatus;

    private String snapshotHash;

    private RuntimeDiffSummary diffSummary;

    private String errorCode;

    private String errorMessage;

    private String operator;

    private LocalDateTime createdAt;
}
