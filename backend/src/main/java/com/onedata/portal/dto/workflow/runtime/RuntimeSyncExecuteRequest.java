package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

/**
 * 执行运行态同步请求
 */
@Data
public class RuntimeSyncExecuteRequest {

    private Long projectCode;

    private Long workflowCode;

    private String operator;

    /**
     * 显式边与血缘推断边不一致时，是否已人工确认差异并允许继续同步
     */
    private Boolean confirmEdgeMismatch;
}
