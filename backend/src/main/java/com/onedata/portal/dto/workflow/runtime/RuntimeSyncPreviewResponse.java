package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行态同步预检响应
 */
@Data
public class RuntimeSyncPreviewResponse {

    private Boolean canSync = false;

    private List<RuntimeSyncIssue> errors = new ArrayList<>();

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private RuntimeDiffSummary diffSummary;

    private List<RuntimeTaskRenamePlan> renamePlan = new ArrayList<>();

    /**
     * 显式边与血缘推断边不一致详情（需要人工确认）
     */
    private RuntimeEdgeMismatchDetail edgeMismatchDetail;
}
