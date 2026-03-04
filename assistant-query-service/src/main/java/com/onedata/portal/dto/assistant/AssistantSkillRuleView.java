package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantSkillRuleView {
    private String skillKey;
    private Boolean enabled;
    private String thresholdJson;
    private Integer version;
}
