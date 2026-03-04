package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantSkillUpdateRequest {
    private Boolean enabled;
    private String thresholdJson;
    private Integer version;
}
