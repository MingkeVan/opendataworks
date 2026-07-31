package com.onedata.portal.dto.execution;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Unified workflow-instance row used by the execution monitor.
 */
@Data
@Builder
public class WorkflowInstanceExecution {

    private Long workflowId;
    private String workflowName;
    private Long workflowCode;
    private Long instanceId;
    private Long localExecutionLogId;
    private String status;
    private String dolphinState;
    private String commandType;
    private String triggerType;
    private String source;
    private String executionSource;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;
    private String errorMessage;
    private Boolean expandable;
}
