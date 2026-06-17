package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.onedata.portal.util.JsonCanonicalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final int SNAPSHOT_SCHEMA_VERSION_DEFINITION = 3;

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowTopologyService workflowTopologyService;
    private final DolphinConfigService dolphinConfigService;
    private final WorkflowQueryService workflowQueryService;
    private final WorkflowTaskRelationService workflowTaskRelationService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowCommandService workflowCommandService;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        return workflowQueryService.list(request);
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        return workflowQueryService.getDetail(workflowId);
    }

    @Transactional
    public String buildDefinitionJsonForExport(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (StringUtils.hasText(workflow.getDefinitionJson())) {
            return workflowDefinitionAssembler.sanitizeDefinitionJsonForExport(workflow.getDefinitionJson());
        }

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> bindings = workflowTaskRelationService.buildTaskBindingsFromRelations(relations);

        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(
                workflowTaskRelationService.collectTaskIds(bindings));
        String definitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, null, bindings, topology);
        workflow.setDefinitionJson(definitionJson);
        dataWorkflowMapper.updateById(workflow);
        return workflowDefinitionAssembler.sanitizeDefinitionJsonForExport(definitionJson);
    }

    @Transactional
    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        DataWorkflow workflow = new DataWorkflow();
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(workflowDefinitionAssembler.defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);
        dataWorkflowMapper.insert(workflow);

        workflowTaskRelationService.persistTaskRelations(workflow.getId(), taskBindings, null, topology);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        String versionDefinitionJson = resolvedDefinitionJson;
        WorkflowVersion version = snapshotWorkflow(workflow, request, versionDefinitionJson);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);

        updateRelationVersion(workflow.getId(), version.getId());
        return workflow;
    }

    public String executeWorkflow(Long workflowId) {
        return workflowExecutionService.executeWorkflow(workflowId);
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        return workflowExecutionService.backfillWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getDolphinConfigId() == null) {
            workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        }
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);

        workflowTaskRelationService.persistTaskRelations(workflowId, taskBindings, workflow.getCurrentVersionId(), topology);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        String versionDefinitionJson = resolvedDefinitionJson;
        if (shouldCreateNewVersion(workflow, versionDefinitionJson)) {
            WorkflowVersion version = snapshotWorkflow(workflow, request, versionDefinitionJson);
            workflow.setCurrentVersionId(version.getId());
            dataWorkflowMapper.updateById(workflow);
            updateRelationVersion(workflowId, version.getId());
        }
        return workflow;
    }

    @Transactional
    public DataWorkflow syncCurrentVersion(Long workflowId, String operator, String triggerSource) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflow.getWorkflowName());
        request.setDescription(workflow.getDescription());
        request.setTaskGroupName(workflow.getTaskGroupName());
        request.setGlobalParams(workflow.getGlobalParams());
        request.setDolphinConfigId(workflow.getDolphinConfigId());
        request.setProjectCode(workflow.getProjectCode());
        request.setTasks(workflowTaskRelationService.buildTaskBindingsFromRelations(relations));
        request.setOperator(resolveWorkflowOperator(workflow, operator));
        request.setTriggerSource(StringUtils.hasText(triggerSource) ? triggerSource.trim() : "publish_auto_save");
        return updateWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow normalizeAndPersistMetadata(Long workflowId, String operator) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> taskBindings = workflowTaskRelationService.buildTaskBindingsFromRelations(relations);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());
        workflow.setDefinitionJson(workflowDefinitionAssembler.resolveDefinitionJson(workflow, null, taskBindings, topology));
        if (StringUtils.hasText(operator)) {
            workflow.setUpdatedBy(operator.trim());
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public DataWorkflow switchSchedulerEngine(Long workflowId, WorkflowSchedulerEngineRequest request) {
        return workflowExecutionService.switchSchedulerEngine(workflowId, request);
    }

    private void updateRelationVersion(Long workflowId, Long versionId) {
        WorkflowTaskRelation update = new WorkflowTaskRelation();
        update.setVersionId(versionId);
        workflowTaskRelationMapper.update(update,
                Wrappers.<WorkflowTaskRelation>lambdaUpdate()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            String snapshotJson) {
        boolean isInitial = workflow.getCurrentVersionId() == null;
        String changeSummary = isInitial ? "initial workflow definition" : "updated workflow definition";
        return workflowVersionService.createVersion(
                workflow.getId(),
                snapshotJson,
                StringUtils.hasText(request.getDescription()) ? request.getDescription() : changeSummary,
                request.getTriggerSource(),
                request.getOperator(),
                SNAPSHOT_SCHEMA_VERSION_DEFINITION,
                null);
    }

    private boolean shouldCreateNewVersion(DataWorkflow workflow, String incomingSnapshotJson) {
        if (workflow == null) {
            return true;
        }
        if (workflow.getCurrentVersionId() == null) {
            return true;
        }
        WorkflowVersion currentVersion = workflowVersionMapper.selectById(workflow.getCurrentVersionId());
        if (currentVersion == null || !StringUtils.hasText(currentVersion.getStructureSnapshot())) {
            return true;
        }
        if (!Objects.equals(currentVersion.getSnapshotSchemaVersion(), SNAPSHOT_SCHEMA_VERSION_DEFINITION)) {
            return true;
        }
        String currentHash = snapshotContentHash(currentVersion.getStructureSnapshot());
        String incomingHash = snapshotContentHash(incomingSnapshotJson);
        if (!StringUtils.hasText(currentHash) || !StringUtils.hasText(incomingHash)) {
            return true;
        }
        return !Objects.equals(currentHash, incomingHash);
    }

    private String snapshotContentHash(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (node != null && node.isObject()) {
                ((ObjectNode) node).remove("meta");
            }
            String normalized = node != null ? JsonCanonicalizer.canonicalize(node) : snapshotJson.trim();
            return JsonCanonicalizer.sha256(normalized);
        } catch (Exception e) {
            // 非法 JSON 时退化为对原始文本取哈希
            log.trace("规范化快照 JSON 失败，回退原始文本哈希", e);
            return JsonCanonicalizer.sha256(snapshotJson.trim());
        }
    }

    private String resolveWorkflowOperator(DataWorkflow workflow, String operator) {
        if (StringUtils.hasText(operator)) {
            return operator.trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getUpdatedBy())) {
            return workflow.getUpdatedBy().trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getCreatedBy())) {
            return workflow.getCreatedBy().trim();
        }
        return "system";
    }

    /**
     * 重新计算工作流中所有任务的上下游关系
     * 用于在单个任务被添加/更新/删除后重新计算整个工作流的关系
     */
    public void refreshTaskRelations(Long workflowId) {
        workflowTaskRelationService.refreshTaskRelations(workflowId);
    }

    private List<WorkflowTaskBinding> normalizeTaskBindings(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks;
    }

    private Long resolveProjectCode(Long requestProjectCode) {
        if (requestProjectCode != null && requestProjectCode > 0) {
            return requestProjectCode;
        }
        return null;
    }

    private Long resolveDolphinConfigId(Long requestDolphinConfigId) {
        if (requestDolphinConfigId != null && requestDolphinConfigId > 0) {
            return dolphinConfigService.getEnabledConfig(requestDolphinConfigId).getId();
        }
        DolphinConfig config = dolphinConfigService.getDefaultConfig();
        return config != null ? config.getId() : null;
    }

    /**
     * 删除工作流
     * 软删除工作流定义；默认保留任务定义以便复用
     */
    @Transactional
    public void deleteWorkflow(Long workflowId) {
        workflowCommandService.deleteWorkflow(workflowId, false);
    }

    /**
     * 删除工作流
     *
     * @param workflowId          工作流ID
     * @param cascadeDeleteTasks  是否级联软删除绑定任务
     */
    @Transactional
    public void deleteWorkflow(Long workflowId, boolean cascadeDeleteTasks) {
        workflowCommandService.deleteWorkflow(workflowId, cascadeDeleteTasks);
    }
}
