package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantMessageSubmitResponse {
    private String sessionId;
    private Long messageId;
    private String runId;
    private String status;
}
