package com.onedata.portal.dto.assistant;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssistantArtifactView {
    private Long id;
    private String runId;
    private String artifactType;
    private String title;
    private String contentJson;
    private LocalDateTime createdAt;
}
