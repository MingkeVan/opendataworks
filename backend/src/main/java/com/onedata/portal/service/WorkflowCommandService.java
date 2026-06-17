package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工作流写命令服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCommandService {

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final DataLineageMapper dataLineageMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;

    public void deleteWorkflow(Long workflowId, boolean cascadeDeleteTasks) {
        if (workflowId == null) {
            throw new IllegalArgumentException("工作流ID不能为空");
        }

        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("工作流不存在: {}", workflowId);
            return;
        }

        List<Long> taskIds = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId))
                .stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        log.info("开始删除工作流: workflowId={}, workflowCode={}, cascadeDeleteTasks={}, taskCount={}",
                workflowId, workflow.getWorkflowCode(), cascadeDeleteTasks, taskIds.size());

        try {
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    boolean dolphinWorkflowExists = dolphinSchedulerService.checkWorkflowExists(workflow.getWorkflowCode());
                    if (!dolphinWorkflowExists) {
                        log.info("DolphinScheduler中不存在工作流，跳过同步删除: {}", workflow.getWorkflowCode());
                    } else {
                        if (workflow.getDolphinScheduleId() != null && workflow.getDolphinScheduleId() > 0) {
                            try {
                                dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinScheduleId());
                            } catch (Exception ex) {
                                log.warn("Failed to offline schedule {} before workflow delete: {}",
                                        workflow.getDolphinScheduleId(), ex.getMessage());
                            }
                        }
                        dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "OFFLINE");
                        dolphinSchedulerService.deleteWorkflow(workflow.getWorkflowCode());
                        log.info("已删除DolphinScheduler中的工作流定义: {}", workflow.getWorkflowCode());
                    }
                } catch (Exception e) {
                    log.warn("删除DolphinScheduler工作流定义失败: {}", e.getMessage());
                }
            }

            if (cascadeDeleteTasks && !taskIds.isEmpty()) {
                dataLineageMapper.delete(
                        Wrappers.<DataLineage>lambdaQuery()
                                .in(DataLineage::getTaskId, taskIds));
                tableTaskRelationMapper.delete(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .in(TableTaskRelation::getTaskId, taskIds));
                dataTaskMapper.deleteBatchIds(taskIds);
                log.info("已级联软删除任务: workflowId={}, taskCount={}", workflowId, taskIds.size());
            }

            workflowTaskRelationMapper.hardDeleteByWorkflowId(workflowId);
            log.info("已删除工作流任务关联关系: workflowId={}", workflowId);

            dataWorkflowMapper.deleteById(workflowId);
            log.info("已软删除工作流定义: {}", workflowId);

            log.info("工作流删除完成: workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("删除工作流失败: {}", workflowId, e);
            throw new RuntimeException("删除工作流失败: " + e.getMessage(), e);
        }
    }
}
