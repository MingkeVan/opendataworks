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

    /**
     * 运行态定义采集模式（当前固定为 export_only）
     */
    private String ingestMode;

    /**
     * 一致性状态: not_checked/consistent/inconsistent
     */
    private String parityStatus = "not_checked";

    /**
     * 导出路径与旧路径一致性摘要
     */
    private RuntimeSyncParitySummary paritySummary;

    private List<RuntimeSyncIssue> errors = new ArrayList<>();

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private RuntimeDiffSummary diffSummary;

    private List<RuntimeTaskRenamePlan> renamePlan = new ArrayList<>();

    /**
     * 显式边与血缘推断边不一致详情（需要人工确认）
     */
    private RuntimeEdgeMismatchDetail edgeMismatchDetail;
}
