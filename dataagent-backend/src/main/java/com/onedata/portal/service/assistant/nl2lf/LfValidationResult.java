package com.onedata.portal.service.assistant.nl2lf;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LfValidationResult {
    private boolean valid = true;
    private boolean blocked = false;
    private boolean needClarification = false;

    private final List<LfValidationIssue> errors = new ArrayList<LfValidationIssue>();
    private final List<LfValidationIssue> warnings = new ArrayList<LfValidationIssue>();
}
