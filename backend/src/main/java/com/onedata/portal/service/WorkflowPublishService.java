package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowApprovalRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.config.DolphinSchedulerProperties;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Objects;

/**
 * 工作流发布 orchestrator（Phase 1：记录 + 状态流转）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowPublishService {

    private final WorkflowPublishRecordMapper publishRecordMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowDeployService workflowDeployService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DolphinSchedulerProperties dolphinSchedulerProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowPublishRecord publish(Long workflowId, WorkflowPublishRequest request) {
        if (!StringUtils.hasText(request.getOperation())) {
            throw new IllegalArgumentException("operation is required");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long versionId = request.getVersionId() != null ? request.getVersionId() : workflow.getCurrentVersionId();
        WorkflowVersion version = versionId == null ? null : workflowVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("Workflow version not found for publish");
        }

        WorkflowPublishRecord record = new WorkflowPublishRecord();
        record.setWorkflowId(workflowId);
        record.setVersionId(version.getId());
        record.setOperation(request.getOperation().toLowerCase());
        record.setTargetEngine("dolphin");
        record.setStatus("pending");
        record.setOperator(request.getOperator());
        publishRecordMapper.insert(record);

        try {
            log.info("Workflow {} publish operation {} initiated for version {}", workflowId, record.getOperation(), version.getVersionNo());
            switch (record.getOperation()) {
                case "deploy":
                    handleDeploy(workflow, version, record, request);
                    break;
                case "online":
                case "offline":
                    invokeDolphin(workflow, record);
                    applyWorkflowStatus(workflow, record);
                    record.setStatus("success");
                    record.setEngineWorkflowCode(workflow.getWorkflowCode());
                    break;
                default:
                    log.warn("Unsupported publish operation {}", record.getOperation());
                    record.setStatus("failed");
                    break;
            }
            publishRecordMapper.updateById(record);
            dataWorkflowMapper.updateById(workflow);
            return record;
        } catch (RuntimeException ex) {
            record.setStatus("failed");
            record.setLog(toJson(Collections.singletonMap("error", ex.getMessage())));
            publishRecordMapper.updateById(record);
            workflow.setPublishStatus("failed");
            dataWorkflowMapper.updateById(workflow);
            throw ex;
        }
    }

    private void applyWorkflowStatus(DataWorkflow workflow, WorkflowPublishRecord record) {
        switch (record.getOperation()) {
            case "deploy":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "online":
                workflow.setStatus("online");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "offline":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                break;
            default:
                log.warn("Unknown workflow publish operation {}", record.getOperation());
        }
    }

    private void invokeDolphin(DataWorkflow workflow, WorkflowPublishRecord record) {
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未 deploy，无法执行 " + record.getOperation());
        }
        try {
            if ("online".equals(record.getOperation())) {
                dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "ONLINE");
            } else if ("offline".equals(record.getOperation())) {
                dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "OFFLINE");
            } else {
                log.debug("No Dolphin action for operation {}", record.getOperation());
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("调用 DolphinScheduler 失败: " + ex.getMessage(), ex);
        }
    }

    private void handleDeploy(DataWorkflow workflow,
                              WorkflowVersion version,
                              WorkflowPublishRecord record,
                              WorkflowPublishRequest request) {
        boolean needApproval = Boolean.TRUE.equals(request.getRequireApproval());
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        if (needApproval && !approved) {
            record.setStatus("pending_approval");
            record.setLog(toJson(Collections.singletonMap("comment", request.getApprovalComment())));
            return;
        }
        performDeploy(workflow, version, record);
    }

    private void performDeploy(DataWorkflow workflow,
                               WorkflowVersion version,
                               WorkflowPublishRecord record) {
        WorkflowDeployService.DeploymentResult result = workflowDeployService.deploy(workflow);
        workflow.setWorkflowCode(result.getWorkflowCode());
        if (result.getProjectCode() != null) {
            workflow.setProjectCode(result.getProjectCode());
        }
        applyWorkflowStatus(workflow, record);
        record.setStatus("success");
        record.setEngineWorkflowCode(result.getWorkflowCode());
        record.setLog(toJson(Collections.singletonMap("taskCount", result.getTaskCount())));
    }

    @Transactional
    public WorkflowPublishRecord approve(Long workflowId,
                                         Long recordId,
                                         WorkflowApprovalRequest request) {
        WorkflowPublishRecord record = publishRecordMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getWorkflowId(), workflowId)) {
            throw new IllegalArgumentException("发布记录不存在");
        }
        if (!"pending_approval".equals(record.getStatus())) {
            throw new IllegalStateException("当前状态不可审批: " + record.getStatus());
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        WorkflowVersion version = workflowVersionMapper.selectById(record.getVersionId());
        if (!Boolean.TRUE.equals(request.getApproved())) {
            record.setStatus("rejected");
            record.setOperator(request.getApprover());
            record.setLog(toJson(Collections.singletonMap("comment", request.getComment())));
            publishRecordMapper.updateById(record);
            return record;
        }

        record.setOperator(request.getApprover());
        performDeploy(workflow, version, record);
        publishRecordMapper.updateById(record);
        dataWorkflowMapper.updateById(workflow);
        return record;
    }

    private Long resolveProjectCode(DataWorkflow workflow) {
        if (workflow.getProjectCode() != null && workflow.getProjectCode() > 0) {
            return workflow.getProjectCode();
        }
        if (dolphinSchedulerProperties.getProjectCode() != null && dolphinSchedulerProperties.getProjectCode() > 0) {
            return dolphinSchedulerProperties.getProjectCode();
        }
        Long resolved = dolphinSchedulerService.getProjectCode();
        if (resolved != null && resolved > 0) {
            workflow.setProjectCode(resolved);
            return resolved;
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
