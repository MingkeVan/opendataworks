package com.onedata.portal.dto.workflow;

import lombok.Data;

import java.util.List;

/**
 * 工作流创建/更新请求
 */
@Data
public class WorkflowDefinitionRequest {

    private Long workflowId;

    private String workflowName;

    private String description;

    private String definitionJson;

    private List<WorkflowTaskBinding> tasks;

    private String operator;

    private String triggerSource = "manual";

    private Long projectCode;
}
