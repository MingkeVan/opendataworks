package com.onedata.portal.dto.workflow;

import lombok.Builder;
import lombok.Data;

/**
 * Simplified Dolphin workflow instance summary returned by dolphinscheduler-service.
 */
@Data
@Builder
public class WorkflowInstanceSummary {

    private Long instanceId;
    private String state;
    private String commandType;
    private Long durationMs;
    private String startTime;
    private String endTime;
    private String rawJson;
}
