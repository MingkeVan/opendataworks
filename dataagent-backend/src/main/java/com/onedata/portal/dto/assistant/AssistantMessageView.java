package com.onedata.portal.dto.assistant;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssistantMessageView {
    private Long id;
    private String sessionId;
    private String runId;
    private String role;
    private String content;
    private String intent;
    private String metadataJson;
    private LocalDateTime createdAt;
}
