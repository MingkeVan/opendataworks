package com.onedata.portal.scheduled;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.service.DolphinSchedulerService;
import com.onedata.portal.service.WorkflowInstanceCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 周期同步 Dolphin 工作流实例的定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionSyncJob {

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowInstanceCacheService cacheService;
    private final DolphinSchedulerService dolphinSchedulerService;

    @Scheduled(cron = "0 */5 * * * ?")
    public void syncRecentInstances() {
        List<DataWorkflow> workflows = dataWorkflowMapper.selectList(
            Wrappers.<DataWorkflow>lambdaQuery()
                .eq(DataWorkflow::getStatus, "online")
                .isNotNull(DataWorkflow::getWorkflowCode)
        );
        for (DataWorkflow workflow : workflows) {
            try {
                List<WorkflowInstanceSummary> instances =
                    dolphinSchedulerService.listWorkflowInstances(workflow.getWorkflowCode(), 10);
                cacheService.replaceCache(workflow, instances);
            } catch (Exception ex) {
                log.warn("Failed to sync workflow {}: {}", workflow.getWorkflowName(), ex.getMessage());
            }
        }
    }
}
