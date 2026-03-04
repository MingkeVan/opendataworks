package com.onedata.portal.service.assistant.nl2lf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.AssistantLlmChatProperties;
import com.onedata.portal.dto.assistant.AssistantContextDTO;
import com.onedata.portal.service.assistant.llm.AssistantLlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "assistant.llm.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmLfPlanner implements LfPlanner {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("(?is)```json\\s*(\\{.*?\\})\\s*```");
    private static final Pattern SQL_PREFIX_PATTERN = Pattern.compile(
        "^(?is)\\s*(select|with|insert|update|delete|create|drop|alter|truncate)\\b");

    private final AssistantLlmClient llmClient;
    private final AssistantLlmChatProperties llmProperties;
    private final DefaultLfPlanner defaultLfPlanner;
    private final ObjectMapper objectMapper;

    @Override
    public LogicalForm draft(NlQueryInput input) {
        if (input == null || !StringUtils.hasText(input.getContent())) {
            return defaultLfPlanner.draft(input);
        }

        String content = input.getContent().trim();
        if (isDirectSql(content)) {
            LogicalForm lf = defaultLfPlanner.draft(input);
            lf.getTrace().put("planner", "sql-direct-bypass");
            lf.getTrace().put("llmEnabled", true);
            return lf;
        }

        try {
            String response = llmClient.completeJson(buildSystemPrompt(), buildUserPrompt(input));
            JsonNode node = parseJson(response);
            LogicalForm lf = fromJson(node, input);
            lf.getTrace().put("planner", "llm-openai-compatible");
            lf.getTrace().put("llmModel", llmProperties.getModel());
            lf.getTrace().put("llmEnabled", true);
            return lf;
        } catch (Exception ex) {
            if (llmProperties.isStrict()) {
                throw new IllegalStateException("LLM 规划失败: " + ex.getMessage(), ex);
            }
            LogicalForm fallback = defaultLfPlanner.draft(input);
            fallback.getTrace().put("planner", "llm-fallback-rule-based");
            fallback.getTrace().put("fallbackReason", abbreviate(ex.getMessage()));
            fallback.getTrace().put("llmEnabled", true);
            return fallback;
        }
    }

    private boolean isDirectSql(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return SQL_PREFIX_PATTERN.matcher(content).find();
    }

    private String buildSystemPrompt() {
        return "你是企业数据分析 SQL 规划器。请根据用户问题和上下文，输出严格 JSON，不要输出任何额外文本。"
            + " JSON Schema:"
            + " {"
            + "\"intent\":\"query|bi_query\","
            + "\"sqlDraft\":\"string\","
            + "\"dimensions\":[{\"name\":\"string\",\"alias\":\"string\"}],"
            + "\"metrics\":[{\"name\":\"string\",\"agg\":\"sum|count|avg|max|min\",\"alias\":\"string\"}],"
            + "\"filters\":[{\"field\":\"string\",\"op\":\"=|!=|>|>=|<|<=|in|between|like\",\"value\":\"string\"}],"
            + "\"groupBy\":[\"string\"],"
            + "\"orderBy\":[{\"field\":\"string\",\"direction\":\"asc|desc\"}],"
            + "\"chartIntent\":{\"preferredType\":\"line|bar|pie|table\"},"
            + "\"clarification\":{\"required\":boolean,\"unmatched\":[\"string\"],\"ambiguous\":[\"string\"],\"lineageMissing\":boolean},"
            + "\"confidence\":{\"overall\":0.0}"
            + " }."
            + " 约束："
            + "1) sqlDraft 必须是单条 SQL；"
            + "2) 默认 LIMIT 200；"
            + "3) 若上下文提供 database，优先使用该库的表；"
            + "4) 无法确定时，clarification.required=true，sqlDraft 仍给出最保守草稿。";
    }

    private String buildUserPrompt(NlQueryInput input) {
        AssistantContextDTO context = input.getContext();
        Map<String, Object> contextMap = new LinkedHashMap<String, Object>();
        if (context != null) {
            contextMap.put("sourceId", context.getSourceId());
            contextMap.put("database", context.getDatabase());
            contextMap.put("mode", context.getMode());
            contextMap.put("limitProfile", context.getLimitProfile());
            contextMap.put("manualLimit", context.getManualLimit());
            contextMap.put("plannerMode", context.getPlannerMode());
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("intent", input.getIntent());
        payload.put("question", input.getContent());
        payload.put("context", contextMap);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"intent\":\"" + nullSafe(input.getIntent()) + "\",\"question\":\""
                + nullSafe(input.getContent()) + "\"}";
        }
    }

    private JsonNode parseJson(String response) throws Exception {
        String text = response == null ? "" : response.trim();
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            text = matcher.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return objectMapper.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private LogicalForm fromJson(JsonNode node, NlQueryInput input) {
        LogicalForm lf = new LogicalForm();
        lf.setIntent(readText(node, "intent", input == null ? "query" : input.getIntent()));
        lf.setRawQuery(input == null ? null : input.getContent());
        lf.setContext(input == null ? null : input.getContext());

        String sqlDraft = readText(node, "sqlDraft", null);
        if (!StringUtils.hasText(sqlDraft)) {
            sqlDraft = readText(node, "sql", null);
        }
        if (!StringUtils.hasText(sqlDraft)) {
            throw new IllegalStateException("LLM 未返回 sqlDraft");
        }
        lf.setSqlDraft(sqlDraft.trim());

        mergeListMap(node.path("entities"), lf.getEntities());
        mergeListMap(node.path("dimensions"), lf.getDimensions());
        mergeListMap(node.path("metrics"), lf.getMetrics());
        mergeListMap(node.path("filters"), lf.getFilters());
        mergeListMap(node.path("joins"), lf.getJoins());
        mergeListString(node.path("groupBy"), lf.getGroupBy());
        mergeListMap(node.path("orderBy"), lf.getOrderBy());

        mergeMap(node.path("chartIntent"), lf.getChartIntent());
        mergeMap(node.path("taskIntent"), lf.getTaskIntent());
        mergeMap(node.path("clarification"), lf.getClarification());
        mergeMap(node.path("confidence"), lf.getConfidence());

        if (!lf.getClarification().containsKey("required")) {
            lf.getClarification().put("required", false);
        }
        if (!lf.getClarification().containsKey("unmatched")) {
            lf.getClarification().put("unmatched", new ArrayList<String>());
        }
        if (!lf.getClarification().containsKey("ambiguous")) {
            lf.getClarification().put("ambiguous", new ArrayList<String>());
        }
        if (!lf.getClarification().containsKey("lineageMissing")) {
            lf.getClarification().put("lineageMissing", false);
        }
        if (!lf.getConfidence().containsKey("overall")) {
            lf.getConfidence().put("overall", 0.8D);
        }
        return lf;
    }

    private void mergeListMap(JsonNode source, List<Map<String, Object>> target) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode node : source) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Map<String, Object> value = objectMapper.convertValue(node, Map.class);
            target.add(value);
        }
    }

    private void mergeListString(JsonNode source, List<String> target) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            String value = item == null ? null : item.asText(null);
            if (StringUtils.hasText(value)) {
                target.add(value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeMap(JsonNode source, Map<String, Object> target) {
        if (source == null || !source.isObject()) {
            return;
        }
        target.putAll(objectMapper.convertValue(source, Map.class));
    }

    private String readText(JsonNode node, String key, String defaultValue) {
        if (node == null) {
            return defaultValue;
        }
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText(null);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= 200) {
            return value;
        }
        return value.substring(0, 200) + "...";
    }
}
