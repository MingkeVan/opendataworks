package com.onedata.portal.controller;

import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewResponse;
import com.onedata.portal.service.WorkflowRuntimeSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行态反向同步 API
 */
@RestController
@RequestMapping("/v1/workflows/runtime")
@RequiredArgsConstructor
public class WorkflowRuntimeSyncController {

    private final WorkflowRuntimeSyncService workflowRuntimeSyncService;

    @GetMapping("/dolphin")
    public Result<PageResult<DolphinRuntimeWorkflowOption>> listDolphinRuntimeWorkflows(
            @RequestParam(required = false) Long projectCode,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        PageResult<DolphinRuntimeWorkflowOption> page =
                workflowRuntimeSyncService.listRuntimeWorkflows(projectCode, pageNum, pageSize, keyword);
        return Result.success(page);
    }

    @PostMapping("/dolphin/preview")
    public Result<RuntimeSyncPreviewResponse> preview(@RequestBody RuntimeSyncPreviewRequest request) {
        return Result.success(workflowRuntimeSyncService.preview(request));
    }

    @PostMapping("/dolphin/sync")
    public Result<RuntimeSyncExecuteResponse> sync(@RequestBody RuntimeSyncExecuteRequest request) {
        return Result.success(workflowRuntimeSyncService.sync(request));
    }
}
