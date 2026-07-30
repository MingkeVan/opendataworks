package com.onedata.portal.service.audit;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保持现有访问口径的轻量 SQL 表引用解析器。
 */
@Component
public class DorisAuditSqlTableParser {

    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile(
            "\\bINSERT\\s+INTO\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_PATTERN = Pattern.compile(
            "\\bUPDATE\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM_PATTERN = Pattern.compile(
            "\\bDELETE\\s+FROM\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    public List<AuditTableReference> parse(String sql, String defaultDatabase) {
        if (!StringUtils.hasText(sql)) {
            return new ArrayList<>();
        }
        Map<String, AuditTableReference> references = new LinkedHashMap<>();
        collect(references, sql, defaultDatabase, FROM_JOIN_PATTERN, true, false);
        collect(references, sql, defaultDatabase, INSERT_INTO_PATTERN, false, true);
        collect(references, sql, defaultDatabase, UPDATE_PATTERN, false, true);
        collect(references, sql, defaultDatabase, DELETE_FROM_PATTERN, false, true);
        return new ArrayList<>(references.values());
    }

    private void collect(Map<String, AuditTableReference> references,
            String sql,
            String defaultDatabase,
            Pattern pattern,
            boolean read,
            boolean write) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String database = normalize(matcher.group(1));
            String table = normalize(matcher.group(2));
            if (!StringUtils.hasText(table)) {
                continue;
            }
            if (!StringUtils.hasText(database)) {
                database = normalize(defaultDatabase);
            }
            if (!StringUtils.hasText(database)) {
                continue;
            }
            String key = database + "." + table;
            String resolvedDatabase = database;
            String resolvedTable = table;
            AuditTableReference reference = references.computeIfAbsent(
                    key, ignored -> new AuditTableReference(resolvedDatabase, resolvedTable));
            reference.setRead(reference.isRead() || read);
            reference.setWrite(reference.isWrite() || write);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }
}
