package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dolphin 运行态工作流选项
 */
@Data
public class DolphinRuntimeWorkflowOption {

    private Long projectCode;

    private Long workflowCode;

    private String workflowName;

    private String releaseState;

    private Boolean synced;

    private Long localWorkflowId;

    private String localWorkflowName;

    private LocalDateTime lastRuntimeSyncAt;
}
