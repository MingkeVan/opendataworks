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
import java.util.regex.Pattern;

/**
 * SQL 查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryService {

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile("\\b(delete|drop|alter)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_START = Pattern.compile("^(select|with|show|describe|explain)\\b", Pattern.CASE_INSENSITIVE);
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 200;
    private static final int PREVIEW_LIMIT = 100;

    private final DorisConnectionService dorisConnectionService;
    private final DorisClusterService dorisClusterService;
    private final DataQueryHistoryMapper historyMapper;
    private final ObjectMapper objectMapper;

    /**
     * 执行查询
     */
    public SqlQueryResponse executeQuery(SqlQueryRequest request) {
        validateSql(request.getSql());
        int limit = resolveLimit(request.getLimit());

        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasMore = false;

        try (Connection connection = dorisConnectionService.getConnection(request.getClusterId(), request.getDatabase());
             Statement statement = connection.createStatement()) {

            statement.setMaxRows(limit + 1);
            String sql = request.getSql().trim();
            log.info("Executing SQL query: {}", abbreviate(sql));

            try (ResultSet resultSet = statement.executeQuery(sql)) {
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
        } catch (SQLException e) {
            log.error("Execute SQL query failed", e);
            throw new RuntimeException("执行 SQL 失败: " + e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - start;

        DataQueryHistory history = saveHistory(request, columns, rows, hasMore, duration);

        SqlQueryResponse response = new SqlQueryResponse();
        response.setColumns(columns);
        response.setRows(rows);
        response.setPreviewRowCount(rows.size());
        response.setHasMore(hasMore);
        response.setDurationMs(duration);
        response.setHistoryId(history.getId());
        response.setExecutedAt(history.getExecutedAt());
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

    private void validateSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new RuntimeException("SQL 不能为空");
        }
        String trimmed = sql.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (lowered.chars().filter(ch -> ch == ';').count() > 1) {
            throw new RuntimeException("仅支持单条 SQL 执行");
        }
        if (DANGEROUS_KEYWORDS.matcher(lowered).find()) {
            throw new RuntimeException("检测到危险 SQL 关键字，请检查后再执行");
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
}
