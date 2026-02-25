package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导出响应
 */
@Data
public class WorkflowExportJsonResponse {

    private String fileName;

    private String content;
}
