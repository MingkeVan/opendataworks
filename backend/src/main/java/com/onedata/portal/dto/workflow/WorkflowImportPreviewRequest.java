package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导入预检请求
 */
@Data
public class WorkflowImportPreviewRequest {

    /**
     * 导入来源：json / dolphin（默认 json）
     */
    private String sourceType;

    /**
     * 工作流定义 JSON 文本（Dolphin 导出或平台同构格式）
     */
    private String definitionJson;

    /**
     * Dolphin 项目编码（sourceType=dolphin 时生效）
     */
    private Long projectCode;

    /**
     * Dolphin 工作流编码（sourceType=dolphin 时必填）
     */
    private Long workflowCode;

    /**
     * 导入后工作流名称（可选，不传时自动取定义名称）
     */
    private String workflowName;

    private String operator;
}
