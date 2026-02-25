package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导入预检请求
 */
@Data
public class WorkflowImportPreviewRequest {

    /**
     * 工作流定义 JSON 文本（Dolphin 导出或平台同构格式）
     */
    private String definitionJson;

    private String operator;
}
