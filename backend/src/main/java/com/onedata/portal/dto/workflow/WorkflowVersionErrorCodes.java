package com.onedata.portal.dto.workflow;

/**
 * 版本能力错误码
 */
public final class WorkflowVersionErrorCodes {

    private WorkflowVersionErrorCodes() {
    }

    public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
    public static final String VERSION_COMPARE_INVALID = "VERSION_COMPARE_INVALID";
    public static final String VERSION_SNAPSHOT_UNSUPPORTED = "VERSION_SNAPSHOT_UNSUPPORTED";
    public static final String VERSION_TASK_NOT_FOUND = "VERSION_TASK_NOT_FOUND";
    public static final String VERSION_ROLLBACK_FAILED = "VERSION_ROLLBACK_FAILED";
}
