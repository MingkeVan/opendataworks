package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.DolphinSchedulerProperties;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将平台工作流定义转换并推送到 DolphinScheduler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDeployService {

    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DataTaskMapper dataTaskMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DolphinSchedulerProperties dolphinSchedulerProperties;

    private final ObjectMapper objectMapper;

    @Transactional
    public DeploymentResult deploy(DataWorkflow workflow) {
        // Ensure project exists (force refresh/create)
        dolphinSchedulerService.getProjectCode(true);

        List<WorkflowTaskRelation> bindings = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflow.getId()));
        if (CollectionUtils.isEmpty(bindings)) {
            throw new IllegalStateException("工作流尚未绑定任何任务");
        }
        List<Long> taskIds = bindings.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            throw new IllegalStateException("任务 ID 列表为空");
        }
        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        Map<Long, DataTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(DataTask::getId, t -> t));
        for (Long taskId : taskIds) {
            if (!taskMap.containsKey(taskId)) {
                throw new IllegalStateException("任务不存在: " + taskId);
            }
        }

        ensureTaskCodes(tasks);

        List<TableTaskRelation> tableRelations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTaskId, taskIds));
        Map<Long, Set<Long>> readTables = new HashMap<>();
        Map<Long, Set<Long>> writeTables = new HashMap<>();
        for (TableTaskRelation relation : tableRelations) {
            if ("read".equalsIgnoreCase(relation.getRelationType())) {
                readTables.computeIfAbsent(relation.getTaskId(), k -> new HashSet<>())
                        .add(relation.getTableId());
            } else if ("write".equalsIgnoreCase(relation.getRelationType())) {
                writeTables.computeIfAbsent(relation.getTaskId(), k -> new HashSet<>())
                        .add(relation.getTableId());
            }
        }

        List<Map<String, Object>> definitions = new ArrayList<>();
        List<DolphinSchedulerService.TaskRelationPayload> relationPayloads = new ArrayList<>();
        List<DolphinSchedulerService.TaskLocationPayload> locationPayloads = new ArrayList<>();

        List<DataTask> orderedTasks = bindings.stream()
                .sorted(Comparator.comparing(WorkflowTaskRelation::getCreatedAt))
                .map(binding -> taskMap.get(binding.getTaskId()))
                .collect(Collectors.toList());

        dolphinSchedulerService.alignSequenceWithExistingTasks(
                orderedTasks.stream()
                        .map(DataTask::getDolphinTaskCode)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));

        // Fetch datasources once
        List<DolphinDatasourceOption> allDatasources = dolphinSchedulerService.listDatasources(null, null);
        Map<String, DolphinDatasourceOption> datasourceMap = allDatasources.stream()
                .collect(Collectors.toMap(DolphinDatasourceOption::getName, opt -> opt,
                        (v1, v2) -> v1));

        int index = 0;
        Map<Long, WorkflowTaskRelation> bindingByTaskId = bindings.stream()
                .collect(Collectors.toMap(WorkflowTaskRelation::getTaskId, b -> b));

        for (DataTask task : orderedTasks) {
            WorkflowTaskRelation binding = bindingByTaskId.get(task.getId());
            NodeAttr attr = parseNodeAttr(binding);
            String nodeType = attr.getNodeType() != null ? attr.getNodeType()
                    : (task.getDolphinNodeType() == null ? "SHELL" : task.getDolphinNodeType());
            String priority = attr.getPriority() != null ? attr.getPriority() : mapPriority(task.getPriority());
            int version = task.getDolphinTaskVersion() == null ? 1 : task.getDolphinTaskVersion();

            String sqlOrScript;
            if ("SQL".equalsIgnoreCase(nodeType)) {
                sqlOrScript = task.getTaskSql();
            } else {
                sqlOrScript = dolphinSchedulerService.buildShellScript(task.getTaskSql());
            }

            Long datasourceId = null;
            String realDatasourceType = null;
            if (StringUtils.hasText(task.getDatasourceName())) {
                DolphinDatasourceOption option = datasourceMap.get(task.getDatasourceName());
                if (option == null) {
                    // Try refreshing list if not found
                    log.debug("Datasource {} not found in cache, refreshing...", task.getDatasourceName());
                    List<DolphinDatasourceOption> refreshed = dolphinSchedulerService.listDatasources(null,
                            task.getDatasourceName());
                    option = refreshed.stream()
                            .filter(d -> Objects.equals(d.getName(), task.getDatasourceName()))
                            .findFirst()
                            .orElse(null);
                }

                if (option != null) {
                    datasourceId = option.getId();
                    realDatasourceType = option.getType();
                }
            }

            if ("SQL".equalsIgnoreCase(nodeType) && datasourceId == null) {
                throw new IllegalStateException(String.format(
                        "Datasource '%s' not found for task '%s'. Please check if the datasource exists in DolphinScheduler.",
                        task.getDatasourceName(), task.getTaskName()));
            }

            Map<String, Object> definition = dolphinSchedulerService.buildTaskDefinition(
                    task.getDolphinTaskCode(),
                    version,
                    task.getTaskName(),
                    task.getTaskDesc(),
                    sqlOrScript,
                    priority,
                    coalesce(attr.getRetryTimes(), task.getRetryTimes(), 0),
                    coalesce(attr.getRetryInterval(), task.getRetryInterval(), 1),
                    coalesce(attr.getTimeoutSeconds(), task.getTimeoutSeconds(), 0),
                    nodeType,
                    datasourceId,
                    realDatasourceType != null ? realDatasourceType : task.getDatasourceType());
            definitions.add(definition);

            List<DataTask> upstreams = resolveUpstreamTasks(task, orderedTasks, readTables, writeTables);
            if (!CollectionUtils.isEmpty(upstreams) && Boolean.TRUE.equals(binding.getIsEntry())) {
                upstreams = Collections.emptyList();
            }
            if (CollectionUtils.isEmpty(upstreams)) {
                relationPayloads.add(dolphinSchedulerService.buildRelation(0L, 0,
                        task.getDolphinTaskCode(), version));
            } else {
                for (DataTask upstream : upstreams) {
                    relationPayloads.add(dolphinSchedulerService.buildRelation(
                            upstream.getDolphinTaskCode(),
                            upstream.getDolphinTaskVersion() == null ? 1 : upstream.getDolphinTaskVersion(),
                            task.getDolphinTaskCode(),
                            version));
                }
            }

            locationPayloads.add(dolphinSchedulerService.buildLocation(
                    task.getDolphinTaskCode(),
                    index++,
                    computeLane(task)));
        }

        long workflowCode = workflow.getWorkflowCode() == null ? 0L : workflow.getWorkflowCode();
        boolean existingWorkflow = workflowCode > 0;
        if (existingWorkflow) {
            log.info("Workflow {} already exists, switch OFFLINE before redeploy", workflowCode);
            dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        }

        long deployedCode = dolphinSchedulerService.syncWorkflow(
                workflowCode,
                workflow.getWorkflowName(),
                definitions,
                relationPayloads,
                locationPayloads);

        updateTaskProcessCode(orderedTasks, deployedCode);

        Long projectCode = workflow.getProjectCode();
        if (projectCode == null || projectCode <= 0) {
            if (dolphinSchedulerProperties.getProjectCode() != null
                    && dolphinSchedulerProperties.getProjectCode() > 0) {
                projectCode = dolphinSchedulerProperties.getProjectCode();
            } else {
                projectCode = dolphinSchedulerService.getProjectCode();
            }
        }
        return DeploymentResult.builder()
                .workflowCode(deployedCode)
                .projectCode(projectCode)
                .taskCount(orderedTasks.size())
                .existingWorkflow(existingWorkflow)
                .build();
    }

    private void ensureTaskCodes(List<DataTask> tasks) {
        List<Long> existingCodes = tasks.stream()
                .map(DataTask::getDolphinTaskCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        dolphinSchedulerService.alignSequenceWithExistingTasks(existingCodes);
        for (DataTask task : tasks) {
            boolean changed = false;
            if (task.getDolphinTaskCode() == null) {
                task.setDolphinTaskCode(dolphinSchedulerService.nextTaskCode());
                changed = true;
            }
            if (task.getDolphinTaskVersion() == null) {
                task.setDolphinTaskVersion(1);
                changed = true;
            }
            if (changed) {
                dataTaskMapper.updateById(task);
            }
        }
    }

    private List<DataTask> resolveUpstreamTasks(DataTask task,
            List<DataTask> scopedTasks,
            Map<Long, Set<Long>> readTables,
            Map<Long, Set<Long>> writeTables) {
        Set<Long> reads = readTables.getOrDefault(task.getId(), Collections.emptySet());
        if (reads.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataTask> upstreams = new ArrayList<>();
        for (DataTask candidate : scopedTasks) {
            if (Objects.equals(candidate.getId(), task.getId())) {
                continue;
            }
            Set<Long> writes = writeTables.getOrDefault(candidate.getId(), Collections.emptySet());
            if (writes.isEmpty()) {
                continue;
            }
            Set<Long> intersection = new HashSet<>(writes);
            intersection.retainAll(reads);
            if (!intersection.isEmpty()) {
                upstreams.add(candidate);
            }
        }
        return upstreams;
    }

    private int computeLane(DataTask task) {
        if (task.getTaskType() == null) {
            return 0;
        }
        switch (task.getTaskType().toLowerCase()) {
            case "stream":
                return 1;
            case "dim":
            case "dimension":
                return 2;
            default:
                return 0;
        }
    }

    private String mapPriority(Integer value) {
        int priority = value == null ? 5 : value;
        if (priority >= 9) {
            return "HIGHEST";
        } else if (priority >= 7) {
            return "HIGH";
        } else if (priority >= 5) {
            return "MEDIUM";
        } else if (priority >= 3) {
            return "LOW";
        }
        return "LOWEST";
    }

    private Integer coalesce(Integer... values) {
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private NodeAttr parseNodeAttr(WorkflowTaskRelation relation) {
        if (relation == null || !StringUtils.hasText(relation.getNodeAttrs())) {
            return new NodeAttr();
        }
        try {
            return objectMapper.readValue(relation.getNodeAttrs(), NodeAttr.class);
        } catch (Exception ex) {
            log.warn("Failed to parse node_attrs for task {}: {}", relation.getTaskId(), ex.getMessage());
            return new NodeAttr();
        }
    }

    private void updateTaskProcessCode(List<DataTask> tasks, long workflowCode) {
        for (DataTask task : tasks) {
            if (!Objects.equals(task.getDolphinProcessCode(), workflowCode)) {
                task.setDolphinProcessCode(workflowCode);
                dataTaskMapper.updateById(task);
            }
        }
    }

    @Data
    public static class DeploymentResult {
        private final Long workflowCode;
        private final Long projectCode;
        private final Integer taskCount;
        private final Boolean existingWorkflow;

        @Builder
        public DeploymentResult(Long workflowCode, Long projectCode, Integer taskCount, Boolean existingWorkflow) {
            this.workflowCode = workflowCode;
            this.projectCode = projectCode;
            this.taskCount = taskCount;
            this.existingWorkflow = existingWorkflow;
        }
    }

    @Data
    private static class NodeAttr {
        private String nodeType;
        private String priority;
        private Integer retryTimes;
        private Integer retryInterval;
        private Integer timeoutSeconds;
    }
}
