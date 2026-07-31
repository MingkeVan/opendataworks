package com.onedata.portal.service.lineage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowLineageConsistencyResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairIssue;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.SqlTableMatcherService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 推断、{@code table_task_relation}、{@code definitionJson} 三方只读比对。
 *
 * <p>只读组件，不写任何表，不依赖 {@code DataTaskService} 与 {@link TaskLineageWriteService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLineageConsistencyChecker {

    /** SQL 已明确匹配，但关系表缺失。block-missing 模式下阻断发布。 */
    public static final String CODE_SQL_RELATION_MISSING = "LINEAGE_SQL_RELATION_MISSING";
    /** 关系表存在，但 SQL 未推断出来。始终只告警。 */
    public static final String CODE_RELATION_EXTRA = "LINEAGE_RELATION_EXTRA";
    /** SQL 存在未匹配或歧义的表引用。始终只告警。 */
    public static final String CODE_SQL_UNRESOLVED = "LINEAGE_SQL_UNRESOLVED";
    /** definitionJson 的表清单与关系表不一致。可走既有元数据修复流程。 */
    public static final String CODE_DEFINITION_DRIFT = "LINEAGE_DEFINITION_DRIFT";

    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_WARNING = "WARNING";

    private final SqlTableMatcherService sqlTableMatcherService;
    private final DataTaskMapper dataTaskMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final LineageConsistencyProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 任务保存前的 SQL 高可信缺失检查。
     *
     * <p>只看 {@code matchStatus=matched} 的表引用。多余血缘、unmatched、ambiguous 一律不参与，
     * 避免用户陷入"存不进、发不了、导不出"的死循环。
     *
     * @return 缺失明细；为空表示通过
     */
    public HighConfidenceGap findHighConfidenceGap(DataTask task,
            Collection<Long> finalInputTableIds,
            Collection<Long> finalOutputTableIds) {
        HighConfidenceGap gap = new HighConfidenceGap();
        if (!isSqlTask(task)) {
            return gap;
        }
        SqlTableAnalyzeResponse analyze = analyzeTaskSql(task);
        if (analyze == null) {
            return gap;
        }
        Set<Long> inputs = toIdSet(finalInputTableIds);
        Set<Long> outputs = toIdSet(finalOutputTableIds);

        for (SqlTableAnalyzeResponse.TableRefMatch ref : matchedRefs(analyze.getInputRefs())) {
            if (!inputs.contains(ref.getChosenTable().getTableId())) {
                gap.getMissingInputs().add(describeRef(ref));
            }
        }
        for (SqlTableAnalyzeResponse.TableRefMatch ref : matchedRefs(analyze.getOutputRefs())) {
            if (!outputs.contains(ref.getChosenTable().getTableId())) {
                gap.getMissingOutputs().add(describeRef(ref));
            }
        }
        return gap;
    }

    /**
     * 工作流级完整报告，供只读接口使用。
     */
    public WorkflowLineageConsistencyResponse report(Long workflowId) {
        WorkflowLineageConsistencyResponse response = new WorkflowLineageConsistencyResponse();
        response.setWorkflowId(workflowId);
        response.setEnforcementMode(properties.getEnforcementMode());

        DataWorkflow workflow = workflowId == null ? null : dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        response.setWorkflowName(workflow.getWorkflowName());

        List<WorkflowPublishRepairIssue> issues = checkWorkflow(workflow, true);
        response.setIssues(issues);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorkflowPublishRepairIssue issue : issues) {
            counts.merge(issue.getCode(), 1, Integer::sum);
        }
        response.setCounts(counts);
        response.setBlocking(hasBlockingIssue(issues));
        return response;
    }

    /**
     * 工作流级一致性比对。
     *
     * @param includeDefinitionDrift deploy 在 {@code syncCurrentVersion()} 之后调用时传 false：
     *                               此时 definitionJson 必然已按关系表重建，再比对没有意义
     */
    public List<WorkflowPublishRepairIssue> checkWorkflow(DataWorkflow workflow, boolean includeDefinitionDrift) {
        if (workflow == null || workflow.getId() == null) {
            return Collections.emptyList();
        }
        List<Long> taskIds = findTaskIds(workflow.getId());
        if (taskIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        Map<Long, Set<Long>> readsByTask = loadRelationMap(taskIds, "read");
        Map<Long, Set<Long>> writesByTask = loadRelationMap(taskIds, "write");

        List<WorkflowPublishRepairIssue> issues = new ArrayList<>();
        for (DataTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            Set<Long> reads = readsByTask.getOrDefault(task.getId(), Collections.emptySet());
            Set<Long> writes = writesByTask.getOrDefault(task.getId(), Collections.emptySet());
            // 非 SQL 节点完全跳过 SQL 比对：analyze() 对非 SQL 返回空结果，
            // 若不跳过，SHELL/PYTHON 等任务的既有血缘会被整体误判成"多余"。
            if (isSqlTask(task)) {
                collectSqlIssues(task, reads, writes, issues);
            }
        }
        if (includeDefinitionDrift) {
            collectDefinitionDriftIssues(workflow, tasks, readsByTask, writesByTask, issues);
        }
        return issues;
    }

    /**
     * 当前模式下是否存在阻断性问题。
     */
    public boolean hasBlockingIssue(Collection<WorkflowPublishRepairIssue> issues) {
        if (CollectionUtils.isEmpty(issues) || !properties.isBlockMissing()) {
            return false;
        }
        return issues.stream()
                .filter(Objects::nonNull)
                .anyMatch(issue -> CODE_SQL_RELATION_MISSING.equals(issue.getCode()));
    }

    private void collectSqlIssues(DataTask task,
            Set<Long> reads,
            Set<Long> writes,
            List<WorkflowPublishRepairIssue> issues) {
        SqlTableAnalyzeResponse analyze = analyzeTaskSql(task);
        if (analyze == null) {
            return;
        }

        Set<Long> sqlInputs = matchedTableIds(analyze.getInputRefs());
        Set<Long> sqlOutputs = matchedTableIds(analyze.getOutputRefs());

        List<String> missingInputs = matchedRefs(analyze.getInputRefs()).stream()
                .filter(ref -> !reads.contains(ref.getChosenTable().getTableId()))
                .map(this::describeRef)
                .collect(Collectors.toList());
        List<String> missingOutputs = matchedRefs(analyze.getOutputRefs()).stream()
                .filter(ref -> !writes.contains(ref.getChosenTable().getTableId()))
                .map(this::describeRef)
                .collect(Collectors.toList());

        if (!missingInputs.isEmpty() || !missingOutputs.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_SQL_RELATION_MISSING,
                    blockMissingSeverity(),
                    false,
                    "task.lineage.missing",
                    String.format("任务[%s] SQL 已解析出的表未登记到血缘：%s。请打开任务，按 SQL 分析结果补齐输入/输出表后重新保存。",
                            taskLabel(task),
                            joinGap(missingInputs, missingOutputs))));
        }

        Set<Long> extraInputs = new LinkedHashSet<>(reads);
        extraInputs.removeAll(sqlInputs);
        Set<Long> extraOutputs = new LinkedHashSet<>(writes);
        extraOutputs.removeAll(sqlOutputs);
        if (!extraInputs.isEmpty() || !extraOutputs.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_RELATION_EXTRA,
                    SEVERITY_WARNING,
                    false,
                    "task.lineage.extra",
                    String.format("任务[%s] 存在 SQL 未推断出的血缘：%s。若为手工补充的依赖可忽略。",
                            taskLabel(task),
                            joinGap(toIdText(extraInputs), toIdText(extraOutputs)))));
        }

        List<String> unresolved = new ArrayList<>();
        if (!CollectionUtils.isEmpty(analyze.getUnmatched())) {
            unresolved.add("未匹配: " + String.join(", ", analyze.getUnmatched()));
        }
        if (!CollectionUtils.isEmpty(analyze.getAmbiguous())) {
            unresolved.add("歧义: " + String.join(", ", analyze.getAmbiguous()));
        }
        if (!unresolved.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_SQL_UNRESOLVED,
                    SEVERITY_WARNING,
                    false,
                    "task.lineage.unresolved",
                    String.format("任务[%s] SQL 中存在无法确定的表引用（%s）。可能是未登记的外部表，血缘可能不完整。",
                            taskLabel(task),
                            String.join("；", unresolved))));
        }
    }

    private void collectDefinitionDriftIssues(DataWorkflow workflow,
            List<DataTask> tasks,
            Map<Long, Set<Long>> readsByTask,
            Map<Long, Set<Long>> writesByTask,
            List<WorkflowPublishRepairIssue> issues) {
        Map<Long, DefinitionTables> definitionTables = loadDefinitionTables(workflow.getDefinitionJson());
        if (definitionTables.isEmpty()) {
            return;
        }
        for (DataTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            DefinitionTables persisted = definitionTables.get(task.getId());
            if (persisted == null) {
                continue;
            }
            Set<Long> reads = readsByTask.getOrDefault(task.getId(), Collections.emptySet());
            Set<Long> writes = writesByTask.getOrDefault(task.getId(), Collections.emptySet());
            if (persisted.getInputs().equals(reads) && persisted.getOutputs().equals(writes)) {
                continue;
            }
            issues.add(buildIssue(task,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson",
                    String.format("任务[%s] 的工作流定义与当前血缘不一致（定义 输入%s/输出%s，血缘 输入%s/输出%s）。",
                            taskLabel(task),
                            persisted.getInputs(),
                            persisted.getOutputs(),
                            reads,
                            writes)));
        }
    }

    private Map<Long, DefinitionTables> loadDefinitionTables(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            JsonNode taskListNode = root == null ? null : root.get("taskDefinitionList");
            if (taskListNode == null || !taskListNode.isArray()) {
                return Collections.emptyMap();
            }
            Map<Long, DefinitionTables> result = new LinkedHashMap<>();
            for (JsonNode taskNode : taskListNode) {
                JsonNode metaNode = taskNode == null ? null : taskNode.get("xPlatformTaskMeta");
                JsonNode taskIdNode = metaNode == null ? null : metaNode.get("taskId");
                if (taskIdNode == null || !taskIdNode.canConvertToLong()) {
                    continue;
                }
                DefinitionTables tables = new DefinitionTables();
                tables.setInputs(readLongSet(taskNode.get("inputTableIds")));
                tables.setOutputs(readLongSet(taskNode.get("outputTableIds")));
                result.put(taskIdNode.asLong(), tables);
            }
            return result;
        } catch (Exception ex) {
            log.debug("Failed to parse definitionJson for lineage drift check: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private Set<Long> readLongSet(JsonNode node) {
        Set<Long> result = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && item.canConvertToLong()) {
                result.add(item.asLong());
            }
        }
        return result;
    }

    private SqlTableAnalyzeResponse analyzeTaskSql(DataTask task) {
        if (!StringUtils.hasText(task.getTaskSql())) {
            return null;
        }
        try {
            return sqlTableMatcherService.analyze(task.getTaskSql(), "SQL");
        } catch (Exception ex) {
            // 解析失败不应阻断保存或发布：这里只做一致性提示，解析器对方言 SQL 存在误判空间。
            log.debug("SQL analyze failed for task {}: {}", task.getId(), ex.getMessage());
            return null;
        }
    }

    private boolean isSqlTask(DataTask task) {
        return task != null && "SQL".equalsIgnoreCase(task.getDolphinNodeType());
    }

    private List<SqlTableAnalyzeResponse.TableRefMatch> matchedRefs(List<SqlTableAnalyzeResponse.TableRefMatch> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return Collections.emptyList();
        }
        return refs.stream()
                .filter(Objects::nonNull)
                .filter(ref -> "matched".equals(ref.getMatchStatus()))
                .filter(ref -> ref.getChosenTable() != null && ref.getChosenTable().getTableId() != null)
                .collect(Collectors.toList());
    }

    private Set<Long> matchedTableIds(List<SqlTableAnalyzeResponse.TableRefMatch> refs) {
        return matchedRefs(refs).stream()
                .map(ref -> ref.getChosenTable().getTableId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Long> findTaskIds(Long workflowId) {
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        return relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<Long, Set<Long>> loadRelationMap(List<Long> taskIds, String relationType) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyMap();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTaskId, taskIds)
                        .eq(TableTaskRelation::getRelationType, relationType));
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (TableTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null || relation.getTableId() == null) {
                continue;
            }
            result.computeIfAbsent(relation.getTaskId(), key -> new LinkedHashSet<>()).add(relation.getTableId());
        }
        return result;
    }

    private WorkflowPublishRepairIssue buildIssue(DataTask task,
            String code,
            String severity,
            boolean repairable,
            String field,
            String message) {
        WorkflowPublishRepairIssue issue = new WorkflowPublishRepairIssue();
        issue.setCode(code);
        issue.setSeverity(severity);
        issue.setRepairable(repairable);
        issue.setField(field);
        issue.setTaskCode(task != null ? task.getDolphinTaskCode() : null);
        issue.setTaskName(task != null ? task.getTaskName() : null);
        issue.setMessage(message);
        return issue;
    }

    private String blockMissingSeverity() {
        return properties.isBlockMissing() ? SEVERITY_ERROR : SEVERITY_WARNING;
    }

    private String describeRef(SqlTableAnalyzeResponse.TableRefMatch ref) {
        SqlTableAnalyzeResponse.TableCandidate chosen = ref.getChosenTable();
        String name = StringUtils.hasText(ref.getRawName()) ? ref.getRawName() : chosen.getTableName();
        return String.format("%s(id=%s)", name, chosen.getTableId());
    }

    private List<String> toIdText(Collection<Long> ids) {
        return ids.stream().map(id -> "id=" + id).collect(Collectors.toList());
    }

    private String joinGap(List<String> inputs, List<String> outputs) {
        List<String> parts = new ArrayList<>();
        if (!inputs.isEmpty()) {
            parts.add("输入 " + String.join(", ", inputs));
        }
        if (!outputs.isEmpty()) {
            parts.add("输出 " + String.join(", ", outputs));
        }
        return String.join("；", parts);
    }

    private String taskLabel(DataTask task) {
        if (task == null) {
            return "unknown-task";
        }
        if (StringUtils.hasText(task.getTaskName())) {
            return task.getTaskName().trim();
        }
        return task.getId() != null ? "task#" + task.getId() : "unknown-task";
    }

    private Set<Long> toIdSet(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptySet();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * SQL 高可信缺失明细。
     */
    @Data
    public static class HighConfidenceGap {
        private final List<String> missingInputs = new ArrayList<>();
        private final List<String> missingOutputs = new ArrayList<>();

        public boolean isEmpty() {
            return missingInputs.isEmpty() && missingOutputs.isEmpty();
        }

        public String describe() {
            List<String> parts = new ArrayList<>();
            if (!missingInputs.isEmpty()) {
                parts.add("输入表 " + String.join(", ", missingInputs));
            }
            if (!missingOutputs.isEmpty()) {
                parts.add("输出表 " + String.join(", ", missingOutputs));
            }
            return String.join("；", parts);
        }
    }

    /**
     * definitionJson 中持久化的表清单。
     */
    @Data
    private static class DefinitionTables {
        private Set<Long> inputs = new LinkedHashSet<>();
        private Set<Long> outputs = new LinkedHashSet<>();
    }
}
