package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.DolphinSchedulerProperties;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[]{
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
    private final DolphinSchedulerProperties dolphinSchedulerProperties;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;

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
                .orderByDesc(WorkflowTaskRelation::getCreatedAt)
        );
        List<WorkflowVersion> versions = workflowVersionService.listByWorkflow(workflowId);
        List<WorkflowPublishRecord> publishRecords = workflowPublishRecordMapper.selectList(
            Wrappers.<WorkflowPublishRecord>lambdaQuery()
                .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                .orderByDesc(WorkflowPublishRecord::getCreatedAt)
        );
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
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(extractTaskIds(request.getTasks(), true)));
        workflow.setExitTaskIds(toJson(extractTaskIds(request.getTasks(), false)));
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        dataWorkflowMapper.insert(workflow);

        persistTaskRelations(workflow.getId(), request.getTasks(), null);

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
        dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "ONLINE");
        return dolphinSchedulerService.startProcessInstance(
            workflowCode,
            null,
            workflow.getWorkflowName()
        );
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(extractTaskIds(request.getTasks(), true)));
        workflow.setExitTaskIds(toJson(extractTaskIds(request.getTasks(), false)));
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }
        dataWorkflowMapper.updateById(workflow);

        persistTaskRelations(workflowId, request.getTasks(), workflow.getCurrentVersionId());

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
            request.getOperator()
        );
    }

    private void persistTaskRelations(Long workflowId,
                                      List<WorkflowTaskBinding> tasks,
                                      Long previousVersionId) {
        workflowTaskRelationMapper.delete(
            Wrappers.<WorkflowTaskRelation>lambdaQuery()
                .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
        );
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        for (WorkflowTaskBinding binding : tasks) {
            if (binding.getTaskId() == null) {
                continue;
            }
            ensureTaskAssignable(binding.getTaskId(), workflowId);
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(binding.getTaskId());
            relation.setIsEntry(Boolean.TRUE.equals(binding.getEntry()));
            relation.setIsExit(Boolean.TRUE.equals(binding.getExit()));
            relation.setNodeAttrs(toJson(binding.getNodeAttrs()));
            relation.setVersionId(previousVersionId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(binding.getTaskId()));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(binding.getTaskId()));
            workflowTaskRelationMapper.insert(relation);
        }
    }

    private List<Long> extractTaskIds(List<WorkflowTaskBinding> tasks, boolean entry) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks.stream()
            .filter(t -> entry ? Boolean.TRUE.equals(t.getEntry()) : Boolean.TRUE.equals(t.getExit()))
            .map(WorkflowTaskBinding::getTaskId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
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
                .eq(WorkflowTaskRelation::getTaskId, taskId)
        );
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
        if (dolphinSchedulerProperties.getProjectCode() != null
            && dolphinSchedulerProperties.getProjectCode() > 0) {
            return dolphinSchedulerProperties.getProjectCode();
        }
        return null;
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
                List<WorkflowInstanceSummary> summaries =
                    dolphinSchedulerService.listWorkflowInstances(workflow.getWorkflowCode(), 1);
                if (!summaries.isEmpty()) {
                    WorkflowInstanceSummary summary = summaries.get(0);
                    applyInstance(
                        workflow,
                        summary.getInstanceId(),
                        summary.getState(),
                        summary.getStartTime(),
                        summary.getEndTime()
                    );
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
        List<WorkflowInstanceSummary> summaries =
            dolphinSchedulerService.listWorkflowInstances(workflow.getWorkflowCode(), limit);
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
}
