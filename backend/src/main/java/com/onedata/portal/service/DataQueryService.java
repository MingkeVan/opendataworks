package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.QueryPreview;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.entity.DataQueryHistory;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataQueryHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * SQL 查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryService {

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
        "\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|grant|revoke|set|use|load|copy|into)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ALLOWED_START = Pattern.compile("^(select|with|show|describe|explain)\\b", Pattern.CASE_INSENSITIVE);
    private static final int MAX_LIMIT = 10000;
    private static final int DEFAULT_LIMIT = 200;
    private static final int PREVIEW_LIMIT = 100;
    private static final int MAX_STATEMENTS = 50;

    private final DorisConnectionService dorisConnectionService;
    private final DorisClusterService dorisClusterService;
    private final DataQueryHistoryMapper historyMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, RunningQuery> runningQueries = new ConcurrentHashMap<>();

    private static class RunningQuery {
        private final Connection connection;
        private final Statement statement;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        private RunningQuery(Connection connection, Statement statement) {
            this.connection = connection;
            this.statement = statement;
        }

        private boolean isCancelRequested() {
            return cancelRequested.get();
        }

        private void cancel() {
            cancelRequested.set(true);
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // ignored
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // ignored
            }
        }
    }

    public boolean stopQuery(String userId, String clientQueryId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(clientQueryId)) {
            return false;
        }
        String key = buildRunningKey(userId, clientQueryId);
        RunningQuery running = runningQueries.get(key);
        if (running == null) {
            return true;
        }
        running.cancel();
        return true;
    }

    /**
     * 执行查询
     */
    public SqlQueryResponse executeQuery(SqlQueryRequest request) {
        if (!StringUtils.hasText(request.getDatabase())) {
            throw new RuntimeException("数据库不能为空");
        }
        List<String> statements = splitStatements(request.getSql());
        if (statements.isEmpty()) {
            throw new RuntimeException("SQL 不能为空");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new RuntimeException("SQL 语句过多，请分批执行");
        }
        statements.forEach(this::validateSql);
        int limit = resolveLimit(request.getLimit());

        long start = System.currentTimeMillis();
        List<com.onedata.portal.dto.SqlQueryResultSet> resultSets = new ArrayList<>();
        boolean cancelled = false;
        String message = "";

        String userId = com.onedata.portal.context.UserContextHolder.getCurrentUserId();
        String clientQueryId = request.getClientQueryId();
        String runningKey = StringUtils.hasText(userId) && StringUtils.hasText(clientQueryId)
            ? buildRunningKey(userId, clientQueryId)
            : null;

        RunningQuery runningQuery = null;

        try (Connection connection = dorisConnectionService.getConnection(request.getClusterId(), request.getDatabase());
             Statement statement = connection.createStatement()) {

            if (runningKey != null) {
                runningQuery = new RunningQuery(connection, statement);
                RunningQuery previous = runningQueries.put(runningKey, runningQuery);
                if (previous != null) {
                    previous.cancel();
                }
            }

            try {
                statement.setQueryTimeout(300);
            } catch (SQLException e) {
                log.debug("JDBC driver does not support query timeout, fallback to socketTimeout only", e);
            }
            statement.setMaxRows(limit + 1);

            int index = 1;
            for (String sql : statements) {
                if (runningQuery != null && runningQuery.isCancelRequested()) {
                    cancelled = true;
                    break;
                }
                String trimmedSql = sql.trim();
                if (!StringUtils.hasText(trimmedSql)) {
                    continue;
                }
                log.info("Executing SQL query: {}", abbreviate(trimmedSql));

                com.onedata.portal.dto.SqlQueryResultSet resultSet = executeStatement(statement, trimmedSql, limit);
                resultSet.setIndex(index++);
                resultSets.add(resultSet);
            }

            if (cancelled && !StringUtils.hasText(message)) {
                message = "查询已停止";
            }
        } catch (SQLException e) {
            if (runningQuery != null && runningQuery.isCancelRequested()) {
                cancelled = true;
                message = "查询已停止";
            } else {
                log.error("Execute SQL query failed", e);
                throw new RuntimeException("执行 SQL 失败: " + e.getMessage(), e);
            }
        } finally {
            if (runningKey != null) {
                runningQueries.remove(runningKey);
            }
        }

        long duration = System.currentTimeMillis() - start;

        SqlQueryResponse response = new SqlQueryResponse();
        response.setResultSets(resultSets);
        response.setResultSetCount(resultSets.size());
        response.setCancelled(cancelled);
        if (!StringUtils.hasText(message)) {
            message = String.format("执行完成：%d 条语句，%d 个结果集", statements.size(), resultSets.size());
        }
        response.setMessage(message);
        response.setDurationMs(duration);

        if (!cancelled) {
            com.onedata.portal.dto.SqlQueryResultSet first = resultSets.isEmpty() ? null : resultSets.get(0);
            List<String> columns = first != null ? first.getColumns() : new ArrayList<>();
            List<Map<String, Object>> rows = first != null ? first.getRows() : new ArrayList<>();
            boolean hasMore = first != null && first.isHasMore();

            DataQueryHistory history = saveHistory(request, columns, rows, hasMore, duration);
            response.setHistoryId(history.getId());
            response.setExecutedAt(history.getExecutedAt());

            // backward compatible fields (first result set)
            response.setColumns(columns);
            response.setRows(rows);
            response.setPreviewRowCount(rows.size());
            response.setHasMore(hasMore);
        } else {
            // cancelled response still returns partial results
            com.onedata.portal.dto.SqlQueryResultSet first = resultSets.isEmpty() ? null : resultSets.get(0);
            List<String> columns = first != null ? first.getColumns() : new ArrayList<>();
            List<Map<String, Object>> rows = first != null ? first.getRows() : new ArrayList<>();
            boolean hasMore = first != null && first.isHasMore();

            response.setColumns(columns);
            response.setRows(rows);
            response.setPreviewRowCount(rows.size());
            response.setHasMore(hasMore);
            response.setExecutedAt(LocalDateTime.now());
        }

        return response;
    }

    /**
     * 查询历史记录
     */
    public Page<DataQueryHistory> listHistory(Integer pageNum, Integer pageSize, Long clusterId, String database) {
        Page<DataQueryHistory> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        LambdaQueryWrapper<DataQueryHistory> wrapper = new LambdaQueryWrapper<DataQueryHistory>()
            .orderByDesc(DataQueryHistory::getExecutedAt);
        if (clusterId != null) {
            wrapper.eq(DataQueryHistory::getClusterId, clusterId);
        }
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataQueryHistory::getDatabaseName, database);
        }
        return historyMapper.selectPage(page, wrapper);
    }

    /**
     * 查询指定用户的历史记录
     */
    public Page<DataQueryHistory> listHistoryByUser(String userId, Integer pageNum, Integer pageSize, Long clusterId, String database) {
        Page<DataQueryHistory> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        LambdaQueryWrapper<DataQueryHistory> wrapper = new LambdaQueryWrapper<DataQueryHistory>()
            .eq(DataQueryHistory::getExecutedBy, userId)
            .orderByDesc(DataQueryHistory::getExecutedAt);
        if (clusterId != null) {
            wrapper.eq(DataQueryHistory::getClusterId, clusterId);
        }
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataQueryHistory::getDatabaseName, database);
        }
        return historyMapper.selectPage(page, wrapper);
    }

    void validateSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new RuntimeException("SQL 不能为空");
        }

        String trimmed = sql.trim();
        String sanitized = stripLiteralsAndComments(trimmed);
        String sanitizedTrimmed = sanitized.trim();

        if (!StringUtils.hasText(sanitizedTrimmed)) {
            throw new RuntimeException("SQL 不能为空");
        }

        String lowered = sanitizedTrimmed.toLowerCase(Locale.ROOT);
        if (DANGEROUS_KEYWORDS.matcher(lowered).find()) {
            throw new RuntimeException("检测到写入或危险 SQL 关键字，请检查后再执行");
        }
        if (!ALLOWED_START.matcher(lowered).find()) {
            throw new RuntimeException("仅支持 SELECT/SHOW/DESCRIBE/EXPLAIN 等只读 SQL");
        }
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private com.onedata.portal.dto.SqlQueryResultSet executeStatement(Statement statement, String sql, int limit) throws SQLException {
        com.onedata.portal.dto.SqlQueryResultSet output = new com.onedata.portal.dto.SqlQueryResultSet();
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasMore = false;

        boolean hasResultSet = statement.execute(sql);
        if (!hasResultSet) {
            output.setColumns(columns);
            output.setRows(rows);
            output.setPreviewRowCount(0);
            output.setHasMore(false);
            return output;
        }

        try (ResultSet resultSet = statement.getResultSet()) {
            if (resultSet == null) {
                output.setColumns(columns);
                output.setRows(rows);
                output.setPreviewRowCount(0);
                output.setHasMore(false);
                return output;
            }
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnLabel(i));
            }

            int rowIndex = 0;
            while (resultSet.next()) {
                if (rowIndex >= limit) {
                    hasMore = true;
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), resultSet.getObject(i));
                }
                rows.add(row);
                rowIndex++;
            }
        }

        output.setColumns(columns);
        output.setRows(rows);
        output.setPreviewRowCount(rows.size());
        output.setHasMore(hasMore);
        return output;
    }

    private DataQueryHistory saveHistory(SqlQueryRequest request, List<String> columns, List<Map<String, Object>> rows, boolean hasMore, long duration) {
        DataQueryHistory history = new DataQueryHistory();
        history.setClusterId(request.getClusterId());
        history.setClusterName(resolveClusterName(request.getClusterId()));
        history.setDatabaseName(request.getDatabase());
        history.setSqlText(request.getSql().trim());
        history.setPreviewRowCount(rows.size());
        history.setDurationMs(duration);
        history.setHasMore(hasMore ? 1 : 0);
        history.setResultPreview(buildPreviewJson(columns, rows));
        history.setExecutedAt(LocalDateTime.now());
        // 从用户上下文获取当前用户ID
        history.setExecutedBy(com.onedata.portal.context.UserContextHolder.getCurrentUserId());
        historyMapper.insert(history);
        return history;
    }

    private String resolveClusterName(Long clusterId) {
        if (clusterId == null) {
            return "默认集群";
        }
        DorisCluster cluster = dorisClusterService.getById(clusterId);
        return cluster != null ? cluster.getClusterName() : "集群#" + clusterId;
    }

    private String buildPreviewJson(List<String> columns, List<Map<String, Object>> rows) {
        List<Map<String, Object>> previewRows = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            if (index >= PREVIEW_LIMIT) {
                break;
            }
            previewRows.add(new LinkedHashMap<>(row));
            index++;
        }
        try {
            return objectMapper.writeValueAsString(new QueryPreview(columns, previewRows));
        } catch (JsonProcessingException e) {
            log.warn("Serialize query preview failed", e);
            return null;
        }
    }

    private String abbreviate(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        String compressed = sql.replaceAll("\\s+", " ").trim();
        return compressed.length() > 200 ? compressed.substring(0, 200) + "..." : compressed;
    }

    private String buildRunningKey(String userId, String clientQueryId) {
        return userId + "::" + clientQueryId;
    }

    private List<String> splitStatements(String sql) {
        if (!StringUtils.hasText(sql)) {
            return new ArrayList<>();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inSingleQuote) {
                current.append(ch);
                if (ch == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                    continue;
                }
                if (ch == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                current.append(ch);
                if (ch == '"' && next == '"') {
                    current.append(next);
                    i++;
                    continue;
                }
                if (ch == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (ch == '-' && next == '-') {
                inLineComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }

            if (ch == '/' && next == '*') {
                inBlockComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }

            if (ch == '\'') {
                inSingleQuote = true;
                current.append(ch);
                continue;
            }

            if (ch == '"') {
                inDoubleQuote = true;
                current.append(ch);
                continue;
            }

            if (ch == ';') {
                String stmt = current.toString().trim();
                if (StringUtils.hasText(stmt)) {
                    statements.add(stmt);
                }
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        String stmt = current.toString().trim();
        if (StringUtils.hasText(stmt)) {
            statements.add(stmt);
        }
        return statements;
    }

    private String stripLiteralsAndComments(String sql) {
        StringBuilder builder = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    builder.append(current);
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inSingleQuote) {
                if (current == '\'' && next == '\'') {
                    i++;
                    continue;
                }
                if (current == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                if (current == '"' && next == '"') {
                    i++;
                    continue;
                }
                if (current == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                inLineComment = true;
                i++;
                builder.append(' ');
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                i++;
                builder.append(' ');
                continue;
            }

            if (current == '\'' || current == '"') {
                if (current == '\'') {
                    inSingleQuote = true;
                } else {
                    inDoubleQuote = true;
                }
                builder.append(' ');
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }
}
