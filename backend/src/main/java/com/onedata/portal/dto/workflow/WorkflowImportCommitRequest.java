package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导入提交请求
 */
@Data
public class WorkflowImportCommitRequest {

    /**
     * 工作流定义 JSON 文本（Dolphin 导出或平台同构格式）
     */
    private String definitionJson;

    /**
     * 关系轨道选择：DECLARED / INFERRED（仅在预检提示差异时必填）
     */
    private String relationDecision;

    private String operator;
}
