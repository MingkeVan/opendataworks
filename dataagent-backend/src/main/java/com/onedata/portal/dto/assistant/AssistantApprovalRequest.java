package com.onedata.portal.dto.assistant;

import lombok.Data;

@Data
public class AssistantApprovalRequest {
    private Boolean approved;
    private String comment;
}
