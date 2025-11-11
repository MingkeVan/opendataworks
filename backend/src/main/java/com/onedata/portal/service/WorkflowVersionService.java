package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作流版本服务
 */
@Service
@RequiredArgsConstructor
public class WorkflowVersionService {

    private final WorkflowVersionMapper workflowVersionMapper;

    @Transactional
    public WorkflowVersion createVersion(Long workflowId,
                                         String snapshot,
                                         String changeSummary,
                                         String triggerSource,
                                         String operator) {
        WorkflowVersion latest = workflowVersionMapper.selectOne(
            Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersionNo)
                .last("limit 1")
        );
        int nextVersion = latest == null ? 1 : latest.getVersionNo() + 1;

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkflowId(workflowId);
        version.setVersionNo(nextVersion);
        version.setStructureSnapshot(snapshot);
        version.setChangeSummary(changeSummary);
        version.setTriggerSource(triggerSource);
        version.setCreatedBy(operator);
        workflowVersionMapper.insert(version);
        return version;
    }

    public List<WorkflowVersion> listByWorkflow(Long workflowId) {
        return workflowVersionMapper.selectList(
            Wrappers.<WorkflowVersion>lambdaQuery()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersionNo)
        );
    }

    public WorkflowVersion getById(Long id) {
        return workflowVersionMapper.selectById(id);
    }
}
