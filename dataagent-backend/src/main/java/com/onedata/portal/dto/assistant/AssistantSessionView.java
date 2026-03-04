package com.onedata.portal.dto.assistant;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssistantSessionView {
    private String sessionId;
    private String title;
    private String mode;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastMessageAt;
    private AssistantContextDTO context;
}
