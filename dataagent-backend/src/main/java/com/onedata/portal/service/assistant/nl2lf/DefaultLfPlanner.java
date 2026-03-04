package com.onedata.portal.service.assistant.nl2lf;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DefaultLfPlanner implements LfPlanner {

    private static final Pattern SQL_CODE_BLOCK_PATTERN = Pattern.compile("(?is)```sql\\s*(.*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?is)```\\s*(.*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z0-9_`\\.]+)");

    @Override
    public LogicalForm draft(NlQueryInput input) {
        LogicalForm lf = new LogicalForm();
        if (input == null) {
            lf.setSqlDraft("SELECT 1 AS value");
            return lf;
        }

        lf.setIntent(input.getIntent());
        lf.setRawQuery(input.getContent());
        lf.setContext(input.getContext());

        String sqlDraft = buildSqlDraft(input.getContent());
        lf.setSqlDraft(sqlDraft);
        lf.getTrace().put("planner", "default-rule-based");
        lf.getConfidence().put("overall", 0.7D);
        return lf;
    }

    private String buildSqlDraft(String content) {
        if (!StringUtils.hasText(content)) {
            return "SELECT 1 AS value";
        }

        Matcher sqlBlock = SQL_CODE_BLOCK_PATTERN.matcher(content);
        if (sqlBlock.find() && StringUtils.hasText(sqlBlock.group(1))) {
            return sqlBlock.group(1).trim();
        }

        Matcher block = CODE_BLOCK_PATTERN.matcher(content);
        if (block.find() && StringUtils.hasText(block.group(1))) {
            return block.group(1).trim();
        }

        String trimmed = content.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("select")
            || lower.startsWith("with")
            || lower.startsWith("insert")
            || lower.startsWith("update")
            || lower.startsWith("delete")
            || lower.startsWith("create")
            || lower.startsWith("drop")
            || lower.startsWith("alter")
            || lower.startsWith("truncate")) {
            return trimmed;
        }

        Matcher tableMatcher = TABLE_PATTERN.matcher(content);
        if (tableMatcher.find()) {
            return "SELECT * FROM " + tableMatcher.group(1) + " LIMIT 200";
        }

        return "SELECT 1 AS value";
    }
}
