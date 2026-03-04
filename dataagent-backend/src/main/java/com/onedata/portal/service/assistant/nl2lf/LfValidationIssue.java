package com.onedata.portal.service.assistant.nl2lf;

import lombok.Data;

@Data
public class LfValidationIssue {
    private String code;
    private String message;
    private String severity;
}
