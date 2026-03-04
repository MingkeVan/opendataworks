package com.onedata.portal.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.context.UserContext;
import com.onedata.portal.context.UserContextHolder;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.dto.assistant.AssistantContextDTO;
import com.onedata.portal.service.assistant.nl2lf.CompiledSql;
import com.onedata.portal.service.assistant.nl2lf.LfGroundingService;
import com.onedata.portal.service.assistant.nl2lf.LfPlanner;
import com.onedata.portal.service.assistant.nl2lf.LfSqlCompiler;
import com.onedata.portal.service.assistant.nl2lf.LfValidationResult;
import com.onedata.portal.service.assistant.nl2lf.LfValidator;
import com.onedata.portal.service.assistant.nl2lf.LogicalForm;
import com.onedata.portal.service.assistant.nl2lf.MetadataContext;
import com.onedata.portal.service.assistant.nl2lf.NlQueryInput;
import com.onedata.portal.service.assistant.nl2lf.PolicyContext;
import com.onedata.portal.entity.AssistantArtifact;
import com.onedata.portal.entity.AssistantMessage;
import com.onedata.portal.entity.AssistantRun;
import com.onedata.portal.entity.AssistantRunStep;
import com.onedata.portal.mapper.AssistantArtifactMapper;
import com.onedata.portal.mapper.AssistantMessageMapper;
import com.onedata.portal.mapper.AssistantRunMapper;
import com.onedata.portal.mapper.AssistantRunStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantOrchestratorService {

    private static final String RUN_STATUS_RUNNING = "running";
    private static final String RUN_STATUS_WAITING_APPROVAL = "waiting_approval";
    private static final String RUN_STATUS_COMPLETED = "completed";
    private static final String RUN_STATUS_FAILED = "failed";
    private static final String RUN_STATUS_CANCELLED = "cancelled";
    private static final String RUN_STATUS_CANCEL_REQUESTED = "cancel_requested";

    private static final String INTENT_QUERY = "query";
    private static final String INTENT_BI = "bi_query";
    private static final String PLANNER_MODE_NL2LF2SQL = "nl2lf2sql";

    private final AssistantRunMapper runMapper;
    private final AssistantRunStepMapper runStepMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantArtifactMapper artifactMapper;

    private final AssistantToolExecutor toolExecutor;
    private final AssistantChartService chartService;
    private final AssistantStreamService streamService;
    private final AssistantSkillService skillService;
    private final LfPlanner lfPlanner;
    private final LfGroundingService lfGroundingService;
    private final LfValidator lfValidator;
    private final LfSqlCompiler lfSqlCompiler;
    private final ObjectMapper objectMapper;

    @Async
    public void startRunAsync(String runId, String userId, String username) {
        executeWithUserContext(userId, username, () -> processNewRun(runId, userId));
    }

    public void handleApproval(String runId, String userId, String username, boolean approved, String comment) {
        AssistantRun run = findRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("运行不存在");
        }
        if (!RUN_STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
            throw new IllegalArgumentException("当前运行不在待审批状态");
        }

        Map<String, Object> approvalData = new LinkedHashMap<String, Object>();
        approvalData.put("approved", approved);
        approvalData.put("comment", comment);
        streamService.sendEvent(runId, "approval_received", approvalData);

        if (!approved) {
            run.setStatus(RUN_STATUS_CANCELLED);
            run.setCompletedAt(LocalDateTime.now());
            run.setErrorMessage("用户拒绝执行");
            run.setPendingPayloadJson(null);
            runMapper.updateById(run);

            appendAssistantMessage(run.getSessionId(), runId, run.getIntent(), "已取消执行，本次 SQL 未被执行。", null);
            streamService.sendEvent(runId, "run_cancelled", mapOf("reason", "approval_rejected"));
            streamService.complete(runId);
            return;
        }

        run.setStatus(RUN_STATUS_RUNNING);
        runMapper.updateById(run);
        resumeAfterApprovalAsync(runId, userId, username);
    }

    public void requestCancel(String runId, String userId) {
        AssistantRun run = runMapper.selectOne(
            new LambdaQueryWrapper<AssistantRun>()
                .eq(AssistantRun::getRunId, runId)
                .eq(AssistantRun::getUserId, userId)
                .last("LIMIT 1")
        );
        if (run == null) {
            throw new IllegalArgumentException("运行不存在");
        }

        if (RUN_STATUS_COMPLETED.equals(run.getStatus())
            || RUN_STATUS_FAILED.equals(run.getStatus())
            || RUN_STATUS_CANCELLED.equals(run.getStatus())) {
            return;
        }

        if (RUN_STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
            run.setStatus(RUN_STATUS_CANCELLED);
            run.setCompletedAt(LocalDateTime.now());
            run.setErrorMessage("用户取消执行");
            run.setPendingPayloadJson(null);
            runMapper.updateById(run);
            streamService.sendEvent(runId, "run_cancelled", mapOf("reason", "manual_cancel"));
            streamService.complete(runId);
            return;
        }

        run.setStatus(RUN_STATUS_CANCEL_REQUESTED);
        runMapper.updateById(run);
        toolExecutor.stopRunQuery(userId, runId);
        streamService.sendEvent(runId, "run_cancel_requested", mapOf("runId", runId));
    }

    @Async
    public void resumeAfterApprovalAsync(String runId, String userId, String username) {
        executeWithUserContext(userId, username, () -> processApprovedRun(runId));
    }

    private void processNewRun(String runId, String userId) {
        AssistantRun run = findRun(runId);
        if (run == null) {
            return;
        }

        run.setStatus(RUN_STATUS_RUNNING);
        runMapper.updateById(run);

        streamService.sendEvent(runId, "run_started", mapOf(
            "runId", runId,
            "status", run.getStatus(),
            "uiType", "plan_summary",
            "phase", "intent",
            "summary", "运行已启动"
        ));

        try {
            Map<String, Object> requestPayload = parseJsonMap(run.getRequestContextJson());
            String content = safeString(requestPayload.get("content"));
            AssistantContextDTO context = parseContext(requestPayload.get("context"));

            String intent = detectIntent(content);
            run.setIntent(intent);
            runMapper.updateById(run);

            recordStep(runId, "intent", "意图识别", "success", "识别意图: " + intent, mapOf("intent", intent));
            recordStep(runId, "plan", "执行规划", "success", "已生成执行计划", mapOf(
                "flow", "intent -> plan -> lf-draft -> lf-grounding -> lf-validation -> lf-compile -> analyze -> execute -> summarize -> chart"
            ));

            ensureNotCancelled(runId);

            SqlDraftPlan sqlDraftPlan = buildSqlDraftPlan(runId, userId, content, intent, context);
            String sqlDraft = sqlDraftPlan.getSql();
            addArtifact(runId, "sql", "SQL 草稿", mapOf(
                "sql", sqlDraft,
                "editable", true,
                "plannerMode", sqlDraftPlan.getPlannerMode(),
                "source", sqlDraftPlan.getSource()
            ));
            recordStep(runId, "draft", "SQL 草稿", "success",
                "已通过 " + sqlDraftPlan.getPlannerMode() + " 生成 SQL 草稿",
                mapOf("sql", sqlDraft, "source", sqlDraftPlan.getSource()));

            ensureNotCancelled(runId);

            SqlAnalyzeResponse analyze = toolExecutor.analyzeSql(runId, sqlDraft, context);
            addArtifact(runId, "analyze", "SQL 分析", analyze);
            recordStep(runId, "analyze", "SQL 检查", "success", "SQL 检查完成", mapOf(
                "blocked", analyze.isBlocked(),
                "riskCount", analyze.getRiskItems() == null ? 0 : analyze.getRiskItems().size(),
                "confirmCount", analyze.getConfirmChallenges() == null ? 0 : analyze.getConfirmChallenges().size()
            ));

            if (analyze.isBlocked()) {
                failRun(run, "SQL 被阻断: " + analyze.getBlockedReason(), intent);
                return;
            }

            if (!CollectionUtils.isEmpty(analyze.getConfirmChallenges())) {
                Map<String, Object> pending = new LinkedHashMap<String, Object>();
                pending.put("sql", sqlDraft);
                pending.put("context", context);
                pending.put("intent", intent);
                pending.put("confirmChallenges", analyze.getConfirmChallenges());

                run.setStatus(RUN_STATUS_WAITING_APPROVAL);
                run.setPendingPayloadJson(toJson(pending));
                runMapper.updateById(run);

                Map<String, Object> approvalPayload = new LinkedHashMap<String, Object>();
                approvalPayload.put("message", "检测到高风险 SQL，需审批后执行");
                approvalPayload.put("sql", sqlDraft);
                approvalPayload.put("confirmChallenges", analyze.getConfirmChallenges());
                approvalPayload.put("mode", run.getPolicyMode());
                approvalPayload.put("uiType", "approval_request");
                approvalPayload.put("phase", "approval");
                approvalPayload.put("summary", "检测到高风险 SQL，等待审批");

                recordStep(runId, "approval", "审批等待", "waiting", "等待用户审批", approvalPayload);
                appendAssistantMessage(run.getSessionId(), runId, intent,
                    "检测到高风险 SQL，已暂停执行。请确认后继续。", toJson(approvalPayload));
                streamService.sendEvent(runId, "need_approval", approvalPayload);
                return;
            }

            executeQueryAndFinalize(run, intent, sqlDraft, context, null);
        } catch (RunCancelledException ex) {
            cancelRun(run, ex.getMessage());
        } catch (Exception ex) {
            log.error("Assistant run failed, runId={}", runId, ex);
            failRun(run, "执行失败: " + ex.getMessage(), run.getIntent());
        }
    }

    private void processApprovedRun(String runId) {
        AssistantRun run = findRun(runId);
        if (run == null) {
            return;
        }

        try {
            ensureNotCancelled(runId);

            JsonNode pendingNode = objectMapper.readTree(run.getPendingPayloadJson());
            String sql = textNode(pendingNode, "sql");
            String intent = textNode(pendingNode, "intent");
            AssistantContextDTO context = objectMapper.convertValue(pendingNode.get("context"), AssistantContextDTO.class);

            List<SqlAnalyzeResponse.ConfirmChallenge> challenges = objectMapper.convertValue(
                pendingNode.get("confirmChallenges"),
                new TypeReference<List<SqlAnalyzeResponse.ConfirmChallenge>>() {
                }
            );

            run.setPendingPayloadJson(null);
            runMapper.updateById(run);

            recordStep(runId, "approval", "审批通过", "success", "用户已审批，继续执行", null);
            streamService.sendEvent(runId, "approval_passed", mapOf(
                "runId", runId,
                "uiType", "tool_result",
                "phase", "approval",
                "summary", "审批通过"
            ));

            executeQueryAndFinalize(run, intent, sql, context, challenges);
        } catch (RunCancelledException ex) {
            cancelRun(run, ex.getMessage());
        } catch (Exception ex) {
            log.error("Resume approved run failed, runId={}", runId, ex);
            failRun(run, "审批后执行失败: " + ex.getMessage(), run.getIntent());
        }
    }

    private void executeQueryAndFinalize(AssistantRun run,
                                         String intent,
                                         String sql,
                                         AssistantContextDTO context,
                                         List<SqlAnalyzeResponse.ConfirmChallenge> challenges) {
        ensureNotCancelled(run.getRunId());

        int retries = resolveMaxRetries(run.getUserId());
        Exception lastError = null;
        SqlQueryResponse queryResponse = null;
        String currentSql = sql;

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                queryResponse = toolExecutor.executeSql(
                    run.getRunId(),
                    currentSql,
                    context,
                    null,
                    challenges,
                    challenges != null && !challenges.isEmpty()
                );
                if (attempt > 1) {
                    recordStep(run.getRunId(), "repair", "SQL 自动修复", "success", "第 " + attempt + " 次尝试执行成功", mapOf("sql", currentSql));
                }
                break;
            } catch (Exception ex) {
                lastError = ex;
                if (attempt >= retries) {
                    break;
                }
                String repaired = repairSql(currentSql, attempt);
                if (repaired.equals(currentSql)) {
                    continue;
                }
                currentSql = repaired;
                addArtifact(run.getRunId(), "sql", "SQL 自动修复草稿", mapOf("attempt", attempt + 1, "sql", currentSql));
                recordStep(run.getRunId(), "repair", "SQL 自动修复", "running", "第 " + attempt + " 次失败，已生成修复草稿", mapOf("sql", currentSql));
            }
        }

        if (queryResponse == null) {
            throw new RuntimeException(lastError == null ? "SQL 执行失败" : lastError.getMessage());
        }

        ensureNotCancelled(run.getRunId());

        addArtifact(run.getRunId(), "query_result", "查询结果", queryResponse);
        recordStep(run.getRunId(), "execute", "SQL 执行", "success", queryResponse.getMessage(), mapOf(
            "resultSetCount", queryResponse.getResultSetCount(),
            "previewRowCount", queryResponse.getPreviewRowCount(),
            "hasMore", queryResponse.isHasMore()
        ));

        String summary = buildSummary(queryResponse);
        recordStep(run.getRunId(), "summarize", "结果总结", "success", summary, null);

        AssistantChartService.ChartBuildResult chart = chartService.buildChart(queryResponse, null);
        if (chart != null && chart.getEchartsOption() != null) {
            Map<String, Object> chartArtifact = new LinkedHashMap<String, Object>();
            chartArtifact.put("chartType", chart.getChartType());
            chartArtifact.put("chart_reasoning", chart.getReasoning());
            chartArtifact.put("chartSpec", chart.getChartSpec());
            chartArtifact.put("echartsOption", chart.getEchartsOption());
            addArtifact(run.getRunId(), "chart", "智能 BI 图表", chartArtifact);
            recordStep(run.getRunId(), "chart", "图表生成", "success", chart.getReasoning(), mapOf("chartType", chart.getChartType()));
            streamService.sendEvent(run.getRunId(), "chart_ready", chartArtifact);
        } else {
            recordStep(run.getRunId(), "chart", "图表生成", "skipped", "结果不足，未生成图表", null);
        }

        String assistantText = summary;
        if (queryResponse.isHasMore()) {
            assistantText = assistantText + "\n结果已截断，可提高 limit 后重新执行。";
        }

        appendAssistantMessage(run.getSessionId(), run.getRunId(), intent, assistantText, null);

        run.setStatus(RUN_STATUS_COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setErrorMessage(null);
        runMapper.updateById(run);

        streamService.sendEvent(run.getRunId(), "run_completed", mapOf(
            "summary", assistantText,
            "uiType", "phase_summary",
            "phase", "finalize"
        ));
        streamService.complete(run.getRunId());
    }

    private void failRun(AssistantRun run, String message, String intent) {
        run.setStatus(RUN_STATUS_FAILED);
        run.setCompletedAt(LocalDateTime.now());
        run.setErrorMessage(message);
        runMapper.updateById(run);

        recordStep(run.getRunId(), "failed", "执行失败", "failed", message, null);
        appendAssistantMessage(run.getSessionId(), run.getRunId(), intent, message, null);
        streamService.sendEvent(run.getRunId(), "run_failed", mapOf(
            "error", message,
            "uiType", "error",
            "phase", "finalize",
            "summary", message
        ));
        streamService.complete(run.getRunId());
    }

    private void cancelRun(AssistantRun run, String reason) {
        run.setStatus(RUN_STATUS_CANCELLED);
        run.setCompletedAt(LocalDateTime.now());
        run.setErrorMessage(reason);
        runMapper.updateById(run);

        recordStep(run.getRunId(), "cancelled", "执行取消", "cancelled", reason, null);
        appendAssistantMessage(run.getSessionId(), run.getRunId(), run.getIntent(), "本次执行已取消。", null);
        streamService.sendEvent(run.getRunId(), "run_cancelled", mapOf(
            "reason", reason,
            "uiType", "error",
            "phase", "finalize",
            "summary", reason
        ));
        streamService.complete(run.getRunId());
    }

    private void ensureNotCancelled(String runId) {
        AssistantRun current = findRun(runId);
        if (current == null) {
            throw new RunCancelledException("运行不存在");
        }
        if (RUN_STATUS_CANCEL_REQUESTED.equals(current.getStatus()) || RUN_STATUS_CANCELLED.equals(current.getStatus())) {
            throw new RunCancelledException("用户取消执行");
        }
    }

    private String detectIntent(String content) {
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (lower.contains("图") || lower.contains("bi") || lower.contains("可视化") || lower.contains("趋势")) {
            return INTENT_BI;
        }
        return INTENT_QUERY;
    }

    private SqlDraftPlan buildSqlDraftPlan(String runId,
                                           String userId,
                                           String content,
                                           String intent,
                                           AssistantContextDTO context) {
        String plannerMode = resolvePlannerMode(context);
        NlQueryInput input = new NlQueryInput();
        input.setContent(content);
        input.setIntent(intent);
        input.setContext(context);

        LogicalForm draft = lfPlanner.draft(input);
        addArtifact(runId, "lf_draft", "LF 草稿", draft);
        recordStep(runId, "lf_draft", "LF 草稿", "success", "已生成 LF 草稿", mapOf("plannerMode", plannerMode));

        LogicalForm grounded = lfGroundingService.ground(draft, toMetadataContext(context));
        addArtifact(runId, "lf_grounded", "LF 绑定", grounded);
        recordStep(runId, "lf_grounding", "LF 绑定", "success", "已完成元数据绑定", mapOf(
            "entityCount", grounded == null || grounded.getEntities() == null ? 0 : grounded.getEntities().size()
        ));

        LfValidationResult validationResult = lfValidator.validate(grounded, toPolicyContext(userId, context));
        addArtifact(runId, "lf_validation_report", "LF 校验报告", validationResult);
        String validationStatus = validationResult.isValid()
            ? "success"
            : (validationResult.isNeedClarification() ? "waiting" : "failed");
        recordStep(runId, "lf_validation", "LF 校验", validationStatus,
            buildValidationSummary(validationResult),
            mapOf(
                "valid", validationResult.isValid(),
                "blocked", validationResult.isBlocked(),
                "needClarification", validationResult.isNeedClarification(),
                "errorCount", validationResult.getErrors().size(),
                "warningCount", validationResult.getWarnings().size()
            ));

        if (validationResult.isBlocked()) {
            throw new IllegalStateException("LF 校验阻断");
        }

        if (!validationResult.isValid() && !validationResult.isNeedClarification()) {
            throw new IllegalStateException("LF 校验未通过");
        }

        CompiledSql compiledSql = lfSqlCompiler.compile(grounded);
        addArtifact(runId, "sql_compiled", "LF 编译 SQL", compiledSql);
        recordStep(runId, "lf_compile", "LF 编译", "success",
            StringUtils.hasText(compiledSql.getExplain()) ? compiledSql.getExplain() : "LF 编译完成",
            mapOf("lineageUsed", compiledSql.isLineageUsed()));

        if (!StringUtils.hasText(compiledSql.getSql())) {
            throw new IllegalStateException("LF 编译结果为空");
        }
        return SqlDraftPlan.of(compiledSql.getSql(), plannerMode, "lf-compiler");
    }

    private MetadataContext toMetadataContext(AssistantContextDTO context) {
        MetadataContext metadataContext = new MetadataContext();
        if (context != null) {
            metadataContext.setSourceId(context.getSourceId());
            metadataContext.setDatabase(context.getDatabase());
        }
        return metadataContext;
    }

    private PolicyContext toPolicyContext(String userId, AssistantContextDTO context) {
        PolicyContext policyContext = new PolicyContext();
        policyContext.setUserId(userId);
        policyContext.setMode(context == null ? null : context.getMode());
        return policyContext;
    }

    private String resolvePlannerMode(AssistantContextDTO context) {
        if (context == null || !StringUtils.hasText(context.getPlannerMode())) {
            return PLANNER_MODE_NL2LF2SQL;
        }
        String mode = context.getPlannerMode().trim().toLowerCase(Locale.ROOT);
        if (!PLANNER_MODE_NL2LF2SQL.equals(mode)) {
            throw new IllegalArgumentException("当前仅支持 plannerMode=nl2lf2sql");
        }
        return mode;
    }

    private String buildValidationSummary(LfValidationResult validationResult) {
        if (validationResult == null) {
            return "LF 校验结果为空";
        }
        if (validationResult.isValid()) {
            return "LF 校验通过";
        }
        if (validationResult.isNeedClarification()) {
            return "LF 需要澄清后再执行";
        }
        if (validationResult.isBlocked()) {
            return "LF 校验阻断";
        }
        return "LF 校验未通过";
    }

    private String repairSql(String sql, int attempt) {
        if (!StringUtils.hasText(sql)) {
            return sql;
        }
        String repaired = sql.trim();
        if (attempt == 1 && repaired.endsWith(";")) {
            return repaired.substring(0, repaired.length() - 1);
        }
        if (attempt == 2) {
            String lower = repaired.toLowerCase(Locale.ROOT);
            if (lower.startsWith("select") && !lower.contains(" limit ")) {
                return repaired + " LIMIT 500";
            }
        }
        return repaired;
    }

    private int resolveMaxRetries(String userId) {
        String thresholdJson = skillService.getThresholdJson(userId, AssistantSkillService.SQL_QUALITY_SKILL);
        if (!StringUtils.hasText(thresholdJson)) {
            return 3;
        }
        try {
            JsonNode node = objectMapper.readTree(thresholdJson);
            int retries = node.path("maxRetries").asInt(3);
            return Math.min(Math.max(retries, 1), 3);
        } catch (Exception ex) {
            return 3;
        }
    }

    private String buildSummary(SqlQueryResponse response) {
        int rows = response.getPreviewRowCount() == null ? 0 : response.getPreviewRowCount();
        int columns = response.getColumns() == null ? 0 : response.getColumns().size();
        String message = "执行完成，返回 " + rows + " 行、" + columns + " 列。";
        if (!CollectionUtils.isEmpty(response.getRows())) {
            Map<String, Object> first = response.getRows().get(0);
            message += " 示例首行: " + abbreviateJson(first);
        }
        return message;
    }

    private void appendAssistantMessage(String sessionId,
                                        String runId,
                                        String intent,
                                        String content,
                                        String metadataJson) {
        AssistantMessage message = new AssistantMessage();
        message.setSessionId(sessionId);
        message.setRunId(runId);
        message.setRoleType("assistant");
        message.setIntent(intent);
        message.setContent(content);
        message.setMetadataJson(metadataJson);
        messageMapper.insert(message);

        streamService.sendEvent(runId, "assistant_message", mapOf(
            "id", message.getId(),
            "content", content,
            "intent", intent,
            "metadataJson", metadataJson
        ));
    }

    private void addArtifact(String runId, String type, String title, Object content) {
        AssistantArtifact artifact = new AssistantArtifact();
        artifact.setRunId(runId);
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setContentJson(toJson(content));
        artifactMapper.insert(artifact);

        streamService.sendEvent(runId, "artifact", mapOf(
            "artifactType", type,
            "title", title,
            "contentJson", artifact.getContentJson(),
            "id", artifact.getId(),
            "uiType", artifactUiType(type),
            "phase", artifactPhase(type),
            "summary", title
        ));
    }

    private void recordStep(String runId,
                            String key,
                            String name,
                            String status,
                            String summary,
                            Object detail) {
        AssistantRunStep step = new AssistantRunStep();
        step.setRunId(runId);
        step.setStepOrder(nextStepOrder(runId));
        step.setStepKey(key);
        step.setStepName(name);
        step.setStatus(status);
        step.setSummary(summary);
        step.setDetailJson(detail == null ? null : toJson(detail));
        runStepMapper.insert(step);

        streamService.sendEvent(runId, "step", mapOf(
            "stepOrder", step.getStepOrder(),
            "stepKey", key,
            "stepName", name,
            "status", status,
            "summary", summary,
            "detailJson", step.getDetailJson(),
            "uiType", "phase_summary",
            "phase", key
        ));
    }

    private String artifactUiType(String artifactType) {
        if ("sql".equals(artifactType) || "sql_compiled".equals(artifactType)) {
            return "sql_draft";
        }
        if ("query_result".equals(artifactType)) {
            return "query_result";
        }
        if ("chart".equals(artifactType)) {
            return "chart_result";
        }
        if ("analyze".equals(artifactType)) {
            return "tool_result";
        }
        return "tool_result";
    }

    private String artifactPhase(String artifactType) {
        if ("sql".equals(artifactType) || "sql_compiled".equals(artifactType)) {
            return "compile";
        }
        if ("query_result".equals(artifactType)) {
            return "execute";
        }
        if ("chart".equals(artifactType)) {
            return "chart";
        }
        if ("analyze".equals(artifactType)) {
            return "analyze";
        }
        return "artifact";
    }

    private int nextStepOrder(String runId) {
        AssistantRunStep latest = runStepMapper.selectOne(
            new LambdaQueryWrapper<AssistantRunStep>()
                .eq(AssistantRunStep::getRunId, runId)
                .orderByDesc(AssistantRunStep::getStepOrder)
                .last("LIMIT 1")
        );
        return latest == null || latest.getStepOrder() == null ? 1 : latest.getStepOrder() + 1;
    }

    private AssistantRun findRun(String runId) {
        return runMapper.selectOne(
            new LambdaQueryWrapper<AssistantRun>()
                .eq(AssistantRun::getRunId, runId)
                .last("LIMIT 1")
        );
    }

    private AssistantContextDTO parseContext(Object contextRaw) {
        if (contextRaw == null) {
            return new AssistantContextDTO();
        }
        return objectMapper.convertValue(contextRaw, AssistantContextDTO.class);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String textNode(JsonNode node, String key) {
        if (node == null || node.get(key) == null || node.get(key).isNull()) {
            return null;
        }
        return node.get(key).asText();
    }

    private void executeWithUserContext(String userId, String username, Runnable runnable) {
        UserContext context = new UserContext(userId, username, userId);
        UserContextHolder.setContext(context);
        try {
            runnable.run();
        } finally {
            UserContextHolder.clear();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String abbreviateJson(Object value) {
        String json = toJson(value);
        if (json.length() <= 200) {
            return json;
        }
        return json.substring(0, 200) + "...";
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static class SqlDraftPlan {
        private String sql;
        private String plannerMode;
        private String source;

        private static SqlDraftPlan of(String sql, String plannerMode, String source) {
            SqlDraftPlan plan = new SqlDraftPlan();
            plan.sql = sql;
            plan.plannerMode = plannerMode;
            plan.source = source;
            return plan;
        }

        private String getSql() {
            return sql;
        }

        private String getPlannerMode() {
            return plannerMode;
        }

        private String getSource() {
            return source;
        }
    }

    private static class RunCancelledException extends RuntimeException {
        private RunCancelledException(String message) {
            super(message);
        }
    }
}
