package com.onedata.portal.dto.assistant;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssistantRunView {
    private String runId;
    private String sessionId;
    private String status;
    private String intent;
    private String policyMode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
