package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.workflow.WorkflowApprovalRequest;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.service.WorkflowPublishService;
import com.onedata.portal.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流管理 API
 */
@RestController
@RequestMapping("/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowPublishService workflowPublishService;

    @GetMapping
    public Result<PageResult<DataWorkflow>> list(WorkflowQueryRequest request) {
        Page<DataWorkflow> page = workflowService.list(request);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<WorkflowDetailResponse> detail(@PathVariable Long id) {
        return Result.success(workflowService.getDetail(id));
    }

    @PostMapping
    public Result<DataWorkflow> create(@RequestBody WorkflowDefinitionRequest request) {
        DataWorkflow workflow = workflowService.createWorkflow(request);
        return Result.success(workflow);
    }

    @PutMapping("/{id}")
    public Result<DataWorkflow> update(@PathVariable Long id,
                                       @RequestBody WorkflowDefinitionRequest request) {
        DataWorkflow workflow = workflowService.updateWorkflow(id, request);
        return Result.success(workflow);
    }

    @PostMapping("/{id}/publish")
    public Result<WorkflowPublishRecord> publish(@PathVariable Long id,
                                                 @RequestBody WorkflowPublishRequest request) {
        WorkflowPublishRecord record = workflowPublishService.publish(id, request);
        return Result.success(record);
    }

    @PostMapping("/{id}/publish/{recordId}/approve")
    public Result<WorkflowPublishRecord> approve(@PathVariable Long id,
                                                 @PathVariable Long recordId,
                                                 @RequestBody WorkflowApprovalRequest request) {
        WorkflowPublishRecord record = workflowPublishService.approve(id, recordId, request);
        return Result.success(record);
    }

    @PostMapping("/{id}/execute")
    public Result<String> execute(@PathVariable Long id) {
        String executionId = workflowService.executeWorkflow(id);
        return Result.success(executionId);
    }
}
