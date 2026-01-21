package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
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
    private final DataWorkflowMapper workflowMapper;
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

        // Use topological sort to determine task order for correct DAG layout
        List<DataTask> allTasks = bindings.stream()
                .map(binding -> taskMap.get(binding.getTaskId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // Get both sorted tasks and their topological levels for layout
        TopologicalSortResult sortResult = topologicalSortWithLevels(allTasks, readTables, writeTables);
        List<DataTask> orderedTasks = sortResult.getSortedTasks();
        Map<Long, Integer> taskLevels = sortResult.getTaskLevels();

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

        Map<Integer, Integer> levelOffsets = new HashMap<>(); // Track Y offset per level
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

            // Use level-based layout: X grows with level (left-to-right), Y offsets for
            // parallel tasks
            int level = taskLevels.getOrDefault(task.getId(), 0);
            int offsetInLevel = levelOffsets.getOrDefault(level, 0);
            levelOffsets.put(level, offsetInLevel + 1);

            locationPayloads.add(dolphinSchedulerService.buildLocation(
                    task.getDolphinTaskCode(),
                    level,
                    offsetInLevel));
        }

        long workflowCode = workflow.getWorkflowCode() == null ? 0L : workflow.getWorkflowCode();
        boolean existingWorkflow = workflowCode > 0;
        if (existingWorkflow) {
            // Check if the workflow definition actually exists in DolphinScheduler
            boolean workflowExists = dolphinSchedulerService.checkWorkflowExists(workflowCode);
            if (!workflowExists) {
                log.warn("Workflow {} no longer exists in DolphinScheduler, creating new definition", workflowCode);
                existingWorkflow = false;
                workflowCode = 0L; // Reset to 0 to force creation
            } else {
                log.info("Workflow {} already exists, switch OFFLINE before redeploy", workflowCode);
                dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
            }
        }

        long deployedCode = dolphinSchedulerService.syncWorkflow(
                workflowCode,
                workflow.getWorkflowName(),
                definitions,
                relationPayloads,
                locationPayloads,
                workflow.getGlobalParams());

        updateTaskProcessCode(orderedTasks, deployedCode);

        // Update task status to published
        updateTaskStatus(orderedTasks);

        // Update workflow code in database if it changed
        // Handle both null and different values safely
        Long oldWorkflowCode = workflow.getWorkflowCode();
        if (oldWorkflowCode == null || deployedCode != oldWorkflowCode.longValue()) {
            workflow.setWorkflowCode(deployedCode);
            workflow.setUpdatedBy("system");
            workflowMapper.updateById(workflow);
            log.info("Updated workflow code from {} to {}", oldWorkflowCode, deployedCode);
        }

        Long projectCode = workflow.getProjectCode();
        if (projectCode == null || projectCode <= 0) {
            projectCode = dolphinSchedulerService.getProjectCode();
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

    /**
     * Result of topological sort containing both sorted tasks and their levels.
     */
    @Data
    private static class TopologicalSortResult {
        private final List<DataTask> sortedTasks;
        private final Map<Long, Integer> taskLevels; // taskId -> topological level (0-based)
    }

    /**
     * Perform topological sort on tasks based on data lineage dependencies.
     * Uses Kahn's algorithm to ensure upstream tasks appear before downstream
     * tasks.
     * Also computes the topological level for each task for proper DAG layout.
     * Level 0 = no upstream dependencies, Level N = max upstream level + 1.
     */
    private TopologicalSortResult topologicalSortWithLevels(List<DataTask> tasks,
            Map<Long, Set<Long>> readTables,
            Map<Long, Set<Long>> writeTables) {
        if (CollectionUtils.isEmpty(tasks)) {
            return new TopologicalSortResult(Collections.emptyList(), Collections.emptyMap());
        }

        Map<Long, DataTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(DataTask::getId, t -> t));

        // Build adjacency graph (upstream -> downstreams) and reverse (downstream ->
        // upstreams)
        Map<Long, List<Long>> adjacency = new HashMap<>();
        Map<Long, List<Long>> reverseAdjacency = new HashMap<>(); // for level calculation
        Map<Long, Integer> inDegree = new HashMap<>();

        // Initialize graph
        for (DataTask task : tasks) {
            inDegree.put(task.getId(), 0);
            adjacency.put(task.getId(), new ArrayList<>());
            reverseAdjacency.put(task.getId(), new ArrayList<>());
        }

        // Populate edges
        for (DataTask task : tasks) {
            List<DataTask> upstreams = resolveUpstreamTasks(task, tasks, readTables, writeTables);
            for (DataTask upstream : upstreams) {
                adjacency.get(upstream.getId()).add(task.getId());
                reverseAdjacency.get(task.getId()).add(upstream.getId());
                inDegree.merge(task.getId(), 1, Integer::sum);
            }
        }

        // Compute topological levels (longest path from any source)
        Map<Long, Integer> taskLevels = new HashMap<>();
        for (DataTask task : tasks) {
            computeLevel(task.getId(), reverseAdjacency, taskLevels);
        }

        // Kahn's Algorithm for sorted order
        java.util.Queue<Long> queue = new java.util.LinkedList<>();

        // Add initial zero-degree nodes in a deterministic order (by level first, then
        // by ID)
        tasks.stream()
                .filter(t -> inDegree.get(t.getId()) == 0)
                .sorted(Comparator.comparing((DataTask t) -> taskLevels.getOrDefault(t.getId(), 0))
                        .thenComparing(DataTask::getId))
                .map(DataTask::getId)
                .forEach(queue::add);

        List<DataTask> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            result.add(taskMap.get(currentId));

            if (adjacency.containsKey(currentId)) {
                // Sort downstreams by level then ID for deterministic order
                List<Long> downstreams = adjacency.get(currentId);
                downstreams.sort(Comparator.comparing((Long id) -> taskLevels.getOrDefault(id, 0))
                        .thenComparing(id -> id));

                for (Long downstreamId : downstreams) {
                    inDegree.put(downstreamId, inDegree.get(downstreamId) - 1);
                    if (inDegree.get(downstreamId) == 0) {
                        queue.add(downstreamId);
                    }
                }
            }
        }

        // Cycle handling: if we didn't visit all nodes, there's a cycle.
        if (result.size() < tasks.size()) {
            log.warn(
                    "Cycle detected or disconnected components in workflow tasks. topologicalSort visited {}/{} tasks.",
                    result.size(), tasks.size());
            Set<Long> processed = result.stream().map(DataTask::getId).collect(Collectors.toSet());

            // Append remaining tasks in level/ID order
            tasks.stream()
                    .filter(t -> !processed.contains(t.getId()))
                    .sorted(Comparator.comparing((DataTask t) -> taskLevels.getOrDefault(t.getId(), 0))
                            .thenComparing(DataTask::getId))
                    .forEach(result::add);
        }

        return new TopologicalSortResult(result, taskLevels);
    }

    /**
     * Recursively compute the topological level of a task.
     * Level = max(upstream levels) + 1, or 0 if no upstreams.
     */
    private int computeLevel(Long taskId, Map<Long, List<Long>> reverseAdjacency, Map<Long, Integer> levels) {
        if (levels.containsKey(taskId)) {
            return levels.get(taskId);
        }

        List<Long> upstreams = reverseAdjacency.getOrDefault(taskId, Collections.emptyList());
        if (upstreams.isEmpty()) {
            levels.put(taskId, 0);
            return 0;
        }

        int maxUpstreamLevel = 0;
        for (Long upstreamId : upstreams) {
            int upstreamLevel = computeLevel(upstreamId, reverseAdjacency, levels);
            maxUpstreamLevel = Math.max(maxUpstreamLevel, upstreamLevel);
        }

        int level = maxUpstreamLevel + 1;
        levels.put(taskId, level);
        return level;
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

    private void updateTaskStatus(List<DataTask> tasks) {
        for (DataTask task : tasks) {
            if (!"published".equals(task.getStatus())) {
                task.setStatus("published");
                dataTaskMapper.updateById(task);
                log.debug("Updated task status to published: taskId={}, taskName={}",
                        task.getId(), task.getTaskName());
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
