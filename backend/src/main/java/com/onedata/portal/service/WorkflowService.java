package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowInstanceCacheMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowInstanceCacheService workflowInstanceCacheService;
    private final ObjectMapper objectMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTopologyService workflowTopologyService;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        LambdaQueryWrapper<DataWorkflow> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(DataWorkflow::getWorkflowName, request.getKeyword());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(DataWorkflow::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(DataWorkflow::getUpdatedAt);
        Page<DataWorkflow> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<DataWorkflow> result = dataWorkflowMapper.selectPage(page, wrapper);
        attachLatestInstanceInfo(result.getRecords());
        return result;
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getCreatedAt));
        List<WorkflowVersion> versions = workflowVersionService.listByWorkflow(workflowId);
        List<WorkflowPublishRecord> publishRecords = workflowPublishRecordMapper.selectList(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt));
        List<WorkflowInstanceCache> recentInstances = workflowInstanceCacheService.listRecent(workflowId, 10);
        if ((recentInstances == null || recentInstances.isEmpty()) && workflow.getWorkflowCode() != null) {
            recentInstances = fetchRecentInstancesFromEngine(workflow, 10);
        }
        return WorkflowDetailResponse.builder()
                .workflow(workflow)
                .taskRelations(relations)
                .versions(versions)
                .publishRecords(publishRecords)
                .recentInstances(recentInstances)
                .build();
    }

    @Transactional
    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        DataWorkflow workflow = new DataWorkflow();
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        dataWorkflowMapper.insert(workflow);

        persistTaskRelations(workflow.getId(), taskBindings, null, topology);

        WorkflowVersion version = snapshotWorkflow(workflow, request);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);

        updateRelationVersion(workflow.getId(), version.getId());
        return workflow;
    }

    public String executeWorkflow(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再执行");
        }
        return dolphinSchedulerService.startProcessInstance(
                workflowCode,
                null,
                workflow.getWorkflowName());
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (request == null) {
            throw new IllegalArgumentException("补数参数不能为空");
        }

        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再补数");
        }
        return dolphinSchedulerService.backfillProcessInstance(workflowCode, request);
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }
        dataWorkflowMapper.updateById(workflow);

        persistTaskRelations(workflowId, taskBindings, workflow.getCurrentVersionId(), topology);

        WorkflowVersion version = snapshotWorkflow(workflow, request);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);
        updateRelationVersion(workflowId, version.getId());
        return workflow;
    }

    private void updateRelationVersion(Long workflowId, Long versionId) {
        WorkflowTaskRelation update = new WorkflowTaskRelation();
        update.setVersionId(versionId);
        workflowTaskRelationMapper.update(update,
                Wrappers.<WorkflowTaskRelation>lambdaUpdate()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow, WorkflowDefinitionRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowId", workflow.getId());
        snapshot.put("workflowName", workflow.getWorkflowName());
        snapshot.put("definitionJson", workflow.getDefinitionJson());
        snapshot.put("taskGroupName", workflow.getTaskGroupName());
        snapshot.put("tasks", request.getTasks());
        snapshot.put("updatedBy", request.getOperator());
        String snapshotJson = toJson(snapshot);
        boolean isInitial = workflow.getCurrentVersionId() == null;
        String changeSummary = isInitial ? "initial workflow definition" : "updated workflow definition";
        return workflowVersionService.createVersion(
                workflow.getId(),
                snapshotJson,
                StringUtils.hasText(request.getDescription()) ? request.getDescription() : changeSummary,
                request.getTriggerSource(),
                request.getOperator());
    }

    private void persistTaskRelations(Long workflowId,
            List<WorkflowTaskBinding> tasks,
            Long previousVersionId,
            WorkflowTopologyResult topology) {
        workflowTaskRelationMapper.delete(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        Set<Long> entrySet = topology != null && topology.getEntryTaskIds() != null
                ? topology.getEntryTaskIds()
                : Collections.emptySet();
        Set<Long> exitSet = topology != null && topology.getExitTaskIds() != null
                ? topology.getExitTaskIds()
                : Collections.emptySet();
        for (WorkflowTaskBinding binding : tasks) {
            if (binding.getTaskId() == null) {
                continue;
            }
            ensureTaskAssignable(binding.getTaskId(), workflowId);
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(binding.getTaskId());
            relation.setIsEntry(entrySet.contains(binding.getTaskId()));
            relation.setIsExit(exitSet.contains(binding.getTaskId()));
            relation.setNodeAttrs(toJson(binding.getNodeAttrs()));
            relation.setVersionId(previousVersionId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(binding.getTaskId()));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(binding.getTaskId()));
            workflowTaskRelationMapper.insert(relation);
        }
    }

    /**
     * 重新计算工作流中所有任务的上下游关系
     * 用于在单个任务被添加/更新/删除后重新计算整个工作流的关系
     */
    public void refreshTaskRelations(Long workflowId) {
        // 获取工作流中的所有任务
        List<WorkflowTaskRelation> existingRelations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));

        // 转换为 List<WorkflowTask bindings>，保留必要的属性
        List<WorkflowTaskBinding> taskBindings = new ArrayList<>();
        Long versionId = null;
        WorkflowTopologyResult topology = null;

        for (WorkflowTaskRelation relation : existingRelations) {
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            // 将原来的 nodeAttrs 转换回 NodeAttrs
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception ex) {
                    // 忽略解析错误，使用空值
                }
            }
            taskBindings.add(binding);
            versionId = relation.getVersionId();
        }

        // 重新构建拓扑信息
        if (!taskBindings.isEmpty()) {
            List<Long> taskIds = taskBindings.stream()
                    .map(WorkflowTaskBinding::getTaskId)
                    .collect(Collectors.toList());
            topology = workflowTopologyService.buildTopology(taskIds);
        }

        // 重新保存所有关系（会先删除再插入）
        persistTaskRelations(workflowId, taskBindings, versionId, topology);
    }

    private List<Long> orderTaskIds(Set<Long> sourceIds, List<Long> taskOrder) {
        if (CollectionUtils.isEmpty(sourceIds) || CollectionUtils.isEmpty(taskOrder)) {
            return CollectionUtils.isEmpty(sourceIds) ? Collections.emptyList() : new ArrayList<>(sourceIds);
        }
        List<Long> ordered = new ArrayList<>();
        taskOrder.forEach(taskId -> {
            if (sourceIds.contains(taskId)) {
                ordered.add(taskId);
            }
        });
        if (ordered.size() < sourceIds.size()) {
            sourceIds.stream()
                    .filter(id -> !ordered.contains(id))
                    .forEach(ordered::add);
        }
        return ordered;
    }

    private List<Long> collectTaskIds(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (WorkflowTaskBinding task : tasks) {
            if (task != null && task.getTaskId() != null) {
                ordered.add(task.getTaskId());
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<WorkflowTaskBinding> normalizeTaskBindings(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize json", e);
        }
    }

    private void ensureTaskAssignable(Long taskId, Long workflowId) {
        DataTask dataTask = dataTaskMapper.selectById(taskId);
        if (dataTask == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        WorkflowTaskRelation existing = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, taskId));
        if (existing != null && !existing.getWorkflowId().equals(workflowId)) {
            throw new IllegalStateException("任务已归属其他工作流, taskId=" + taskId);
        }
    }

    private String defaultJson(String definitionJson) {
        return StringUtils.hasText(definitionJson) ? definitionJson : "{}";
    }

    private Long resolveProjectCode(Long requestProjectCode) {
        if (requestProjectCode != null && requestProjectCode > 0) {
            return requestProjectCode;
        }
        return dolphinSchedulerService.getProjectCode();
    }

    private void attachLatestInstanceInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        for (DataWorkflow workflow : workflows) {
            if (workflow.getId() == null) {
                continue;
            }
            WorkflowInstanceCache latest = workflowInstanceCacheService.findLatest(workflow.getId());
            if (latest != null) {
                applyInstance(workflow,
                        latest.getInstanceId(),
                        latest.getState(),
                        latest.getStartTime(),
                        latest.getEndTime());
                continue;
            }
            if (workflow.getWorkflowCode() != null) {
                List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                        .listWorkflowInstances(workflow.getWorkflowCode(), 1);
                if (!summaries.isEmpty()) {
                    WorkflowInstanceSummary summary = summaries.get(0);
                    applyInstance(
                            workflow,
                            summary.getInstanceId(),
                            summary.getState(),
                            summary.getStartTime(),
                            summary.getEndTime());
                }
            }
        }
    }

    private void applyInstance(DataWorkflow workflow,
            Long instanceId,
            String state,
            Object start,
            Object end) {
        workflow.setLatestInstanceId(instanceId);
        workflow.setLatestInstanceState(state);
        workflow.setLatestInstanceStartTime(toLocalDateTime(start));
        workflow.setLatestInstanceEndTime(toLocalDateTime(end));
    }

    private LocalDateTime toLocalDateTime(Object temporal) {
        if (temporal == null) {
            return null;
        }
        if (temporal instanceof LocalDateTime) {
            return (LocalDateTime) temporal;
        }
        if (temporal instanceof Date) {
            return ((Date) temporal).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (temporal instanceof String) {
            String value = (String) temporal;
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return parseFlexibleDateTime(value);
            } catch (DateTimeParseException ignore) {
                return null;
            }
        }
        return null;
    }

    private List<WorkflowInstanceCache> fetchRecentInstancesFromEngine(DataWorkflow workflow, int limit) {
        if (workflow.getWorkflowCode() == null) {
            return Collections.emptyList();
        }
        List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                .listWorkflowInstances(workflow.getWorkflowCode(), limit);
        if (CollectionUtils.isEmpty(summaries)) {
            return Collections.emptyList();
        }
        return summaries.stream()
                .map(summary -> mapSummaryToCache(workflow.getId(), summary))
                .collect(Collectors.toList());
    }

    private WorkflowInstanceCache mapSummaryToCache(Long workflowId, WorkflowInstanceSummary summary) {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflowId);
        cache.setInstanceId(summary.getInstanceId());
        cache.setState(summary.getState());
        cache.setTriggerType(summary.getCommandType());
        cache.setDurationMs(summary.getDurationMs());
        cache.setStartTime(parseToDate(summary.getStartTime()));
        cache.setEndTime(parseToDate(summary.getEndTime()));
        cache.setExtra(summary.getRawJson());
        return cache;
    }

    private Date parseToDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            LocalDateTime ldt = parseFlexibleDateTime(text);
            if (ldt == null) {
                return null;
            }
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        String candidate = raw.replace("Z", "");
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        return null;
    }

    /**
     * 删除工作流
     * 只删除工作流相关数据，保留任务定义以便复用
     */
    @Transactional
    public void deleteWorkflow(Long workflowId) {
        if (workflowId == null) {
            throw new IllegalArgumentException("工作流ID不能为空");
        }

        // 查询工作流信息
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("工作流不存在: {}", workflowId);
            return;
        }

        log.info("开始删除工作流: {}, code: {}", workflowId, workflow.getWorkflowCode());

        try {
            // Step 1: 删除DolphinScheduler中的工作流定义
            if (workflow.getWorkflowCode() != null) {
                try {
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
                } catch (Exception e) {
                    log.warn("删除DolphinScheduler工作流定义失败: {}", e.getMessage());
                    // 继续清理其他数据
                }
            }

            // Step 2: 删除工作流任务关联关系
            workflowTaskRelationMapper.delete(
                    new LambdaQueryWrapper<WorkflowTaskRelation>()
                            .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
            log.info("已删除工作流任务关联关系");

            // Step 3: 删除工作流版本记录
            workflowVersionService.deleteByWorkflowId(workflowId);
            log.info("已删除工作流版本记录");

            // Step 4: 删除工作流发布记录
            workflowPublishRecordMapper.delete(
                    new LambdaQueryWrapper<WorkflowPublishRecord>()
                            .eq(WorkflowPublishRecord::getWorkflowId, workflowId));
            log.info("已删除工作流发布记录");

            // Step 5: 删除工作流执行历史缓存
            workflowInstanceCacheService.deleteByWorkflowId(workflowId);
            log.info("已删除工作流执行历史缓存");

            // Step 6: 删除工作流定义本身
            dataWorkflowMapper.deleteById(workflowId);
            log.info("已删除工作流定义: {}", workflowId);

            log.info("工作流删除完成: {}", workflowId);
        } catch (Exception e) {
            log.error("删除工作流失败: {}", workflowId, e);
            throw new RuntimeException("删除工作流失败: " + e.getMessage(), e);
        }
    }
}
