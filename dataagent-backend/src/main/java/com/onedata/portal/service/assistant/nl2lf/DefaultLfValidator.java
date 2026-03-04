package com.onedata.portal.service.assistant.nl2lf;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DefaultLfValidator implements LfValidator {

    @Override
    public LfValidationResult validate(LogicalForm lf, PolicyContext policyContext) {
        LfValidationResult result = new LfValidationResult();
        if (lf == null) {
            result.setValid(false);
            result.getErrors().add(issue("LF_EMPTY", "LF 为空", "ERROR"));
            return result;
        }

        if (!StringUtils.hasText(lf.getSqlDraft())) {
            result.setValid(false);
            result.getErrors().add(issue("LF_SQL_EMPTY", "LF 未生成 SQL 草稿", "ERROR"));
        }

        Object required = lf.getClarification().get("required");
        if (Boolean.TRUE.equals(required)) {
            result.setNeedClarification(true);
            result.setValid(false);
            result.getWarnings().add(issue("LF_NEED_CLARIFICATION", "LF 绑定存在歧义，建议用户澄清", "WARNING"));
        }

        return result;
    }

    private LfValidationIssue issue(String code, String message, String severity) {
        LfValidationIssue issue = new LfValidationIssue();
        issue.setCode(code);
        issue.setMessage(message);
        issue.setSeverity(severity);
        return issue;
    }
}
