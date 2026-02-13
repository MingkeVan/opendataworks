package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步记录详情
 */
@Data
public class RuntimeSyncRecordDetailResponse {

    private Long id;

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private Long versionId;

    private String status;

    private String snapshotHash;

    private String snapshotJson;

    private RuntimeDiffSummary diffSummary;

    private String errorCode;

    private String errorMessage;

    private String operator;

    private LocalDateTime createdAt;
}
