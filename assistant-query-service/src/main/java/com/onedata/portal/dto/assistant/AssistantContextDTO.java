package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantContextDTO {
    private Long sourceId;
    private String database;
    private String limitProfile;
    private Integer manualLimit;
    private String mode;
    private String plannerMode;
}
