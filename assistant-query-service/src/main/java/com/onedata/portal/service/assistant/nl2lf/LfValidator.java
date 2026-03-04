package com.onedata.portal.service.assistant.nl2lf;

public interface LfValidator {
    LfValidationResult validate(LogicalForm lf, PolicyContext policyContext);
}
