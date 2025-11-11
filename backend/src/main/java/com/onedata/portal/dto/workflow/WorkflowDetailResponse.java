package com.onedata.portal.dto.workflow;

import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作流详情响应
 */
@Data
@Builder
public class WorkflowDetailResponse {

    private DataWorkflow workflow;

    private List<WorkflowTaskRelation> taskRelations;

    private List<WorkflowVersion> versions;

    private List<WorkflowPublishRecord> publishRecords;

    private List<WorkflowInstanceCache> recentInstances;
}
