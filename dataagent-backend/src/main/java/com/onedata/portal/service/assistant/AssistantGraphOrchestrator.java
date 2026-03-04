package com.onedata.portal.service.assistant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantGraphOrchestrator {

    private final AssistantOrchestratorService orchestratorService;
    private CompiledGraph compiledGraph;

    private static final String NODE_INTENT = "IntentNode";
    private static final String NODE_PLAN = "PlanNode";
    private static final String NODE_KNOWLEDGE_RECALL = "KnowledgeRecallNode";
    private static final String NODE_LF_DRAFT = "LfDraftNode";
    private static final String NODE_LF_GROUNDING = "LfGroundingNode";
    private static final String NODE_LF_VALIDATION = "LfValidationNode";
    private static final String NODE_SQL_COMPILE = "SqlCompileNode";
    private static final String NODE_SQL_ANALYZE = "SqlAnalyzeNode";
    private static final String NODE_APPROVAL_GATE = "ApprovalGateNode";
    private static final String NODE_SQL_EXECUTE = "SqlExecuteNode";
    private static final String NODE_SUMMARIZE = "SummarizeNode";
    private static final String NODE_CHART_BUILD = "ChartBuildNode";
    private static final String NODE_FINALIZE = "FinalizeNode";

    @PostConstruct
    public void init() {
        this.compiledGraph = compileGraph();
    }

    public void start(String runId, String userId, String username) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("runId", runId);
        input.put("userId", userId);
        input.put("username", username);
        input.put("phaseTrace", new ArrayList<String>());

        try {
            compiledGraph.invoke(input);
        } catch (Exception ex) {
            log.error("Assistant graph execution failed, runId={}", runId, ex);
            throw new IllegalStateException("智能助理执行图编排失败", ex);
        }
    }

    public void approve(String runId, String userId, String username, boolean approved, String comment) {
        orchestratorService.handleApproval(runId, userId, username, approved, comment);
    }

    public void cancel(String runId, String userId) {
        orchestratorService.requestCancel(runId, userId);
    }

    private CompiledGraph compileGraph() {
        StateGraph graph = new StateGraph();
        try {
            graph.addNode(NODE_INTENT, (AsyncNodeAction) this::intentNode);
            graph.addNode(NODE_PLAN, (AsyncNodeAction) this::planNode);
            graph.addNode(NODE_KNOWLEDGE_RECALL, (AsyncNodeAction) this::knowledgeRecallNode);
            graph.addNode(NODE_LF_DRAFT, (AsyncNodeAction) this::lfDraftNode);
            graph.addNode(NODE_LF_GROUNDING, (AsyncNodeAction) this::lfGroundingNode);
            graph.addNode(NODE_LF_VALIDATION, (AsyncNodeAction) this::lfValidationNode);
            graph.addNode(NODE_SQL_COMPILE, (AsyncNodeAction) this::sqlCompileNode);
            graph.addNode(NODE_SQL_ANALYZE, (AsyncNodeAction) this::sqlAnalyzeNode);
            graph.addNode(NODE_APPROVAL_GATE, (AsyncNodeAction) this::approvalGateNode);
            graph.addNode(NODE_SQL_EXECUTE, (AsyncNodeAction) this::sqlExecuteNode);
            graph.addNode(NODE_SUMMARIZE, (AsyncNodeAction) this::summarizeNode);
            graph.addNode(NODE_CHART_BUILD, (AsyncNodeAction) this::chartBuildNode);
            graph.addNode(NODE_FINALIZE, (AsyncNodeAction) this::finalizeNode);

            graph.addEdge(StateGraph.START, NODE_INTENT);
            graph.addEdge(NODE_INTENT, NODE_PLAN);
            graph.addEdge(NODE_PLAN, NODE_KNOWLEDGE_RECALL);
            graph.addEdge(NODE_KNOWLEDGE_RECALL, NODE_LF_DRAFT);
            graph.addEdge(NODE_LF_DRAFT, NODE_LF_GROUNDING);
            graph.addEdge(NODE_LF_GROUNDING, NODE_LF_VALIDATION);
            graph.addEdge(NODE_LF_VALIDATION, NODE_SQL_COMPILE);
            graph.addEdge(NODE_SQL_COMPILE, NODE_SQL_ANALYZE);
            graph.addEdge(NODE_SQL_ANALYZE, NODE_APPROVAL_GATE);
            graph.addEdge(NODE_APPROVAL_GATE, NODE_SQL_EXECUTE);
            graph.addEdge(NODE_SQL_EXECUTE, NODE_SUMMARIZE);
            graph.addEdge(NODE_SUMMARIZE, NODE_CHART_BUILD);
            graph.addEdge(NODE_CHART_BUILD, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, StateGraph.END);
            return graph.compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException("构建 Assistant Graph 失败", ex);
        }
    }

    private CompletableFuture<Map<String, Object>> intentNode(OverAllState state) {
        return completed(updatePhase(state, NODE_INTENT, "识别用户意图"));
    }

    private CompletableFuture<Map<String, Object>> planNode(OverAllState state) {
        Map<String, Object> update = updatePhase(state, NODE_PLAN, "生成执行计划");
        update.put("planSummary",
            "intent -> plan -> knowledgeRecall -> lfDraft -> lfGrounding -> lfValidation -> sqlCompile -> sqlAnalyze -> approvalGate -> sqlExecute -> summarize -> chartBuild -> finalize");
        return completed(update);
    }

    private CompletableFuture<Map<String, Object>> knowledgeRecallNode(OverAllState state) {
        return completed(updatePhase(state, NODE_KNOWLEDGE_RECALL, "召回元数据与知识"));
    }

    private CompletableFuture<Map<String, Object>> lfDraftNode(OverAllState state) {
        return completed(updatePhase(state, NODE_LF_DRAFT, "生成逻辑表达草稿"));
    }

    private CompletableFuture<Map<String, Object>> lfGroundingNode(OverAllState state) {
        return completed(updatePhase(state, NODE_LF_GROUNDING, "执行元数据与血缘绑定"));
    }

    private CompletableFuture<Map<String, Object>> lfValidationNode(OverAllState state) {
        return completed(updatePhase(state, NODE_LF_VALIDATION, "执行规则校验与风险检查"));
    }

    private CompletableFuture<Map<String, Object>> sqlCompileNode(OverAllState state) {
        return completed(updatePhase(state, NODE_SQL_COMPILE, "编译 SQL 草稿"));
    }

    private CompletableFuture<Map<String, Object>> sqlAnalyzeNode(OverAllState state) {
        return completed(updatePhase(state, NODE_SQL_ANALYZE, "调用 SQL Analyze 工具"));
    }

    private CompletableFuture<Map<String, Object>> approvalGateNode(OverAllState state) {
        return completed(updatePhase(state, NODE_APPROVAL_GATE, "执行审批门禁策略"));
    }

    private CompletableFuture<Map<String, Object>> sqlExecuteNode(OverAllState state) {
        return completed(updatePhase(state, NODE_SQL_EXECUTE, "执行 SQL 查询"));
    }

    private CompletableFuture<Map<String, Object>> summarizeNode(OverAllState state) {
        return completed(updatePhase(state, NODE_SUMMARIZE, "生成结果总结"));
    }

    private CompletableFuture<Map<String, Object>> chartBuildNode(OverAllState state) {
        return completed(updatePhase(state, NODE_CHART_BUILD, "生成图表配置"));
    }

    private CompletableFuture<Map<String, Object>> finalizeNode(OverAllState state) {
        String runId = asString(state.data().get("runId"));
        String userId = asString(state.data().get("userId"));
        String username = asString(state.data().get("username"));
        orchestratorService.startRunAsync(runId, userId, username);
        return completed(updatePhase(state, NODE_FINALIZE, "启动异步执行链路"));
    }

    private Map<String, Object> updatePhase(OverAllState state, String phase, String summary) {
        List<String> trace = readTrace(state.data().get("phaseTrace"));
        trace.add(phase);

        Map<String, Object> update = new LinkedHashMap<String, Object>();
        update.put("phase", phase);
        update.put("phaseSummary", summary);
        update.put("phaseTrace", trace);
        return update;
    }

    @SuppressWarnings("unchecked")
    private List<String> readTrace(Object raw) {
        if (raw instanceof List) {
            return new ArrayList<String>((List<String>) raw);
        }
        return new ArrayList<String>();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private CompletableFuture<Map<String, Object>> completed(Map<String, Object> update) {
        return CompletableFuture.completedFuture(update);
    }
}
