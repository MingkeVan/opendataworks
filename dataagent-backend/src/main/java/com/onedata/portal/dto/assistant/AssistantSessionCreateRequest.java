package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantSessionCreateRequest {
    private String title;
    private AssistantContextDTO context;
}
