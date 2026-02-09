package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.DashboardTableAccessItem;
import com.onedata.portal.dto.DashboardTableAccessSummary;
import com.onedata.portal.dto.TableAccessStats;
import com.onedata.portal.dto.TableAccessTrendPoint;
import com.onedata.portal.dto.TableAccessUserStat;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Doris 表访问统计服务（面向 Doris 2.0.x）
 * <p>
 * 统计策略：
 * 1. 基础访问频次：SHOW QUERY STATS（Doris 原生命令，覆盖所有接入渠道）；
 * 2. 最近访问时间/冷热判断：优先读取审计表（__internal_schema.audit_log 或 doris_audit_db__.doris_audit_tbl__）；
 * 3. 审计不可用时降级为 QUERY STATS 结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisTableAccessService {

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

    private static final int MAX_AUDIT_SCAN_ROWS = 200000;
    private static final long AUDIT_SOURCE_CACHE_MILLIS = 5 * 60 * 1000L;

    private final DorisConnectionService dorisConnectionService;
    private final DataTableMapper dataTableMapper;

    private final Map<Long, CachedAuditSource> auditSourceCache = new ConcurrentHashMap<>();

    public TableAccessStats getTableAccessStats(DataTable table, Long requestedClusterId, int recentDays, int trendDays,
            int topUsers) {
        if (table == null) {
            throw new IllegalArgumentException("表信息不能为空");
        }
        if (!StringUtils.hasText(table.getDbName()) || !StringUtils.hasText(table.getTableName())) {
            throw new IllegalArgumentException("表缺少 database/tableName 信息");
        }
        Long clusterId = resolveClusterId(table, requestedClusterId);
        String database = normalizeIdentifier(table.getDbName());
        String tableName = normalizeIdentifier(extractTableName(table.getTableName()));
        String targetIdentifier = buildIdentifier(database, tableName);

        int safeRecentDays = Math.max(1, Math.min(recentDays, 365));
        int safeTrendDays = Math.max(1, Math.min(trendDays, 90));
        int safeTopUsers = Math.max(1, Math.min(topUsers, 20));

        TableAccessStats stats = new TableAccessStats();
        stats.setTableId(table.getId());
        stats.setClusterId(clusterId);
        stats.setDatabaseName(database);
        stats.setTableName(tableName);
        stats.setRecentDays(safeRecentDays);
        stats.setTrendDays(safeTrendDays);
        stats.setTotalAccessCount(0L);
        stats.setRecentAccessCount(0L);
        stats.setAccessCount7d(0L);
        stats.setAccessCount30d(0L);
        stats.setDistinctUserCount(0L);

        Map<String, Long> queryStatsByTable = loadQueryStatsByDatabase(clusterId, database);
        stats.setTotalAccessCount(queryStatsByTable.getOrDefault(tableName, 0L));

        Optional<AuditSource> auditSource = resolveAuditSource(clusterId);
        if (!auditSource.isPresent()) {
            stats.setDorisAuditEnabled(false);
            stats.setNote("当前 Doris 未检测到可查询的审计表，最近访问时间与趋势不可用；访问次数来自 SHOW QUERY STATS。");
            return stats;
        }

        stats.setDorisAuditEnabled(true);
        stats.setDorisAuditSource(auditSource.get().qualifiedName());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime auditScanStart = now.minusDays(Math.max(30, Math.max(safeRecentDays, safeTrendDays)));
        List<AuditEntry> entries = queryAuditEntries(clusterId, auditSource.get(), auditScanStart);

        long recentCount = 0L;
        long count7d = 0L;
        long count30d = 0L;
        long matchedTotal = 0L;
        long durationSum = 0L;
        long durationCount = 0L;
        LocalDateTime lastAccess = null;
        LocalDateTime firstAccess = null;
        LocalDateTime recentStart = now.minusDays(safeRecentDays);
        LocalDateTime days7Start = now.minusDays(7);
        LocalDateTime days30Start = now.minusDays(30);
        LocalDateTime trendStart = now.minusDays(safeTrendDays - 1L);

        Map<String, Long> userCounter = new HashMap<>();
        Map<String, LocalDateTime> userLastAccess = new HashMap<>();
        Map<LocalDate, Long> trendCounter = new HashMap<>();

        for (AuditEntry entry : entries) {
            if (entry.getTime() == null || !StringUtils.hasText(entry.getStmt())) {
                continue;
            }
            Set<String> referenced = extractIdentifiers(entry.getStmt(), entry.getDatabaseName());
            if (!referenced.contains(targetIdentifier)) {
                continue;
            }

            matchedTotal++;

            if (lastAccess == null || entry.getTime().isAfter(lastAccess)) {
                lastAccess = entry.getTime();
            }
            if (firstAccess == null || entry.getTime().isBefore(firstAccess)) {
                firstAccess = entry.getTime();
            }

            if (!entry.getTime().isBefore(recentStart)) {
                recentCount++;
                if (StringUtils.hasText(entry.getUser())) {
                    userCounter.merge(entry.getUser(), 1L, Long::sum);
                    LocalDateTime previous = userLastAccess.get(entry.getUser());
                    if (previous == null || entry.getTime().isAfter(previous)) {
                        userLastAccess.put(entry.getUser(), entry.getTime());
                    }
                }
                if (entry.getQueryTimeMs() != null && entry.getQueryTimeMs() >= 0) {
                    durationSum += entry.getQueryTimeMs();
                    durationCount++;
                }
            }

            if (!entry.getTime().isBefore(days7Start)) {
                count7d++;
            }
            if (!entry.getTime().isBefore(days30Start)) {
                count30d++;
            }
            if (!entry.getTime().isBefore(trendStart)) {
                trendCounter.merge(entry.getTime().toLocalDate(), 1L, Long::sum);
            }
        }

        // 总访问次数优先采用 SHOW QUERY STATS；若 SHOW 无值则使用审计匹配结果兜底
        if (stats.getTotalAccessCount() == null || stats.getTotalAccessCount() <= 0L) {
            stats.setTotalAccessCount(matchedTotal);
        }
        stats.setRecentAccessCount(recentCount);
        stats.setAccessCount7d(count7d);
        stats.setAccessCount30d(count30d);
        stats.setLastAccessTime(lastAccess);
        stats.setFirstAccessTime(firstAccess);
        stats.setDistinctUserCount((long) userCounter.size());

        if (durationCount > 0L) {
            stats.setAverageDurationMs(BigDecimal.valueOf(durationSum)
                    .divide(BigDecimal.valueOf(durationCount), 2, RoundingMode.HALF_UP));
        }

        List<TableAccessTrendPoint> trendPoints = new ArrayList<>();
        for (int i = safeTrendDays - 1; i >= 0; i--) {
            LocalDate day = now.toLocalDate().minusDays(i);
            TableAccessTrendPoint point = new TableAccessTrendPoint();
            point.setDate(day.toString());
            point.setAccessCount(trendCounter.getOrDefault(day, 0L));
            trendPoints.add(point);
        }
        stats.setTrend(trendPoints);

        List<TableAccessUserStat> topUserStats = userCounter.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(safeTopUsers)
                .map(entry -> {
                    TableAccessUserStat item = new TableAccessUserStat();
                    item.setUserId(entry.getKey());
                    item.setAccessCount(entry.getValue());
                    item.setLastAccessTime(userLastAccess.get(entry.getKey()));
                    return item;
                })
                .collect(Collectors.toList());
        stats.setTopUsers(topUserStats);

        return stats;
    }

    public DashboardTableAccessSummary getDashboardAccessSummary(Long clusterId, int hotDays, int hotLimit, int coldDays,
            int coldLimit) {
        int safeHotDays = Math.max(1, Math.min(hotDays, 365));
        int safeColdDays = Math.max(1, Math.min(coldDays, 365));
        int safeHotLimit = Math.max(1, Math.min(hotLimit, 50));
        int safeColdLimit = Math.max(1, Math.min(coldLimit, 50));

        DashboardTableAccessSummary summary = new DashboardTableAccessSummary();
        summary.setHotWindowDays(safeHotDays);
        summary.setColdWindowDays(safeColdDays);

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
                .isNotNull(DataTable::getClusterId)
                .isNotNull(DataTable::getDbName)
                .isNotNull(DataTable::getTableName)
                .ne(DataTable::getStatus, "deprecated");
        if (clusterId != null) {
            tableWrapper.eq(DataTable::getClusterId, clusterId);
        }
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);
        if (tables.isEmpty()) {
            summary.setDorisAuditEnabled(false);
            summary.setNote("暂无可统计的数据表。");
            return summary;
        }

        Map<String, DataTable> tableIndex = new LinkedHashMap<>();
        Map<Long, Map<String, Set<String>>> clusterDbTables = new HashMap<>();
        for (DataTable table : tables) {
            Long tableClusterId = table.getClusterId();
            if (tableClusterId == null) {
                continue;
            }
            String db = normalizeIdentifier(table.getDbName());
            String name = normalizeIdentifier(extractTableName(table.getTableName()));
            if (!StringUtils.hasText(db) || !StringUtils.hasText(name)) {
                continue;
            }
            String key = buildClusterIdentifier(tableClusterId, db, name);
            tableIndex.put(key, table);
            clusterDbTables.computeIfAbsent(tableClusterId, k -> new HashMap<>())
                    .computeIfAbsent(db, k -> new HashSet<>())
                    .add(name);
        }

        Map<String, Long> totalQueryCount = new HashMap<>();
        for (Map.Entry<Long, Map<String, Set<String>>> clusterEntry : clusterDbTables.entrySet()) {
            Long statClusterId = clusterEntry.getKey();
            for (String db : clusterEntry.getValue().keySet()) {
                Map<String, Long> stats = loadQueryStatsByDatabase(statClusterId, db);
                for (Map.Entry<String, Long> item : stats.entrySet()) {
                    totalQueryCount.put(buildClusterIdentifier(statClusterId, db, item.getKey()), item.getValue());
                }
            }
        }

        boolean hasAnyAudit = false;
        String auditSourceName = null;
        Set<Long> auditEnabledClusters = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scanStart = now.minusDays(Math.max(safeHotDays, safeColdDays));
        LocalDateTime hotStart = now.minusDays(safeHotDays);
        LocalDateTime coldThreshold = now.minusDays(safeColdDays);

        Map<String, Long> hotWindowCount = new HashMap<>();
        Map<String, LocalDateTime> lastAccess = new HashMap<>();

        for (Long auditClusterId : clusterDbTables.keySet()) {
            Optional<AuditSource> auditSource = resolveAuditSource(auditClusterId);
            if (!auditSource.isPresent()) {
                continue;
            }
            hasAnyAudit = true;
            auditEnabledClusters.add(auditClusterId);
            if (auditSourceName == null) {
                auditSourceName = auditSource.get().qualifiedName();
            }

            List<AuditEntry> entries = queryAuditEntries(auditClusterId, auditSource.get(), scanStart);
            Set<String> tableIdentifiers = new HashSet<>();
            for (Map.Entry<String, Set<String>> dbTables : clusterDbTables.get(auditClusterId).entrySet()) {
                for (String table : dbTables.getValue()) {
                    tableIdentifiers.add(buildIdentifier(dbTables.getKey(), table));
                }
            }

            for (AuditEntry entry : entries) {
                if (entry.getTime() == null || !StringUtils.hasText(entry.getStmt())) {
                    continue;
                }
                Set<String> refs = extractIdentifiers(entry.getStmt(), entry.getDatabaseName());
                if (refs.isEmpty()) {
                    continue;
                }
                for (String ref : refs) {
                    if (!tableIdentifiers.contains(ref)) {
                        continue;
                    }
                    String key = buildClusterIdentifier(auditClusterId, ref);
                    LocalDateTime previous = lastAccess.get(key);
                    if (previous == null || entry.getTime().isAfter(previous)) {
                        lastAccess.put(key, entry.getTime());
                    }
                    if (!entry.getTime().isBefore(hotStart)) {
                        hotWindowCount.merge(key, 1L, Long::sum);
                    }
                }
            }
        }

        summary.setDorisAuditEnabled(hasAnyAudit);
        summary.setDorisAuditSource(auditSourceName);
        if (!hasAnyAudit) {
            summary.setNote("未检测到 Doris 审计表，热点/冷表基于 SHOW QUERY STATS（无最后访问时间）。");
        } else if (auditEnabledClusters.size() < clusterDbTables.size()) {
            summary.setNote("部分集群未开启审计表，已对开启审计的集群使用审计统计，其他集群回退至 SHOW QUERY STATS。");
        }

        List<DashboardTableAccessItem> hotItems = new ArrayList<>();
        for (Map.Entry<String, DataTable> entry : tableIndex.entrySet()) {
            String key = entry.getKey();
            DataTable table = entry.getValue();
            boolean clusterAuditEnabled = auditEnabledClusters.contains(table.getClusterId());
            Long count = clusterAuditEnabled
                    ? hotWindowCount.getOrDefault(key, 0L)
                    : totalQueryCount.getOrDefault(key, 0L);
            DashboardTableAccessItem item = toDashboardItem(
                    table,
                    count,
                    clusterAuditEnabled ? lastAccess.get(key) : null,
                    now);
            hotItems.add(item);
        }
        hotItems.sort((a, b) -> Long.compare(
                b.getAccessCount() == null ? 0L : b.getAccessCount(),
                a.getAccessCount() == null ? 0L : a.getAccessCount()));
        summary.setHotTables(hotItems.stream().limit(safeHotLimit).collect(Collectors.toList()));

        List<DashboardTableAccessItem> coldItems = new ArrayList<>();
        for (Map.Entry<String, DataTable> entry : tableIndex.entrySet()) {
            String key = entry.getKey();
            DataTable table = entry.getValue();
            LocalDateTime last = lastAccess.get(key);
            boolean clusterAuditEnabled = auditEnabledClusters.contains(table.getClusterId());
            Long count = clusterAuditEnabled
                    ? hotWindowCount.getOrDefault(key, 0L)
                    : totalQueryCount.getOrDefault(key, 0L);

            boolean isCold;
            if (clusterAuditEnabled) {
                isCold = (last == null || last.isBefore(coldThreshold));
            } else {
                isCold = count == null || count <= 0L;
            }
            if (!isCold) {
                continue;
            }
            coldItems.add(toDashboardItem(table, count, last, now));
        }

        coldItems.sort((a, b) -> {
            if (a.getLastAccessTime() == null && b.getLastAccessTime() == null) {
                return 0;
            }
            if (a.getLastAccessTime() == null) {
                return -1;
            }
            if (b.getLastAccessTime() == null) {
                return 1;
            }
            return a.getLastAccessTime().compareTo(b.getLastAccessTime());
        });
        summary.setLongUnusedTables(coldItems.stream().limit(safeColdLimit).collect(Collectors.toList()));

        return summary;
    }

    private DashboardTableAccessItem toDashboardItem(DataTable table, Long count, LocalDateTime lastAccess,
            LocalDateTime now) {
        DashboardTableAccessItem item = new DashboardTableAccessItem();
        item.setTableId(table.getId());
        item.setClusterId(table.getClusterId());
        item.setDbName(table.getDbName());
        item.setTableName(extractTableName(table.getTableName()));
        item.setLayer(table.getLayer());
        item.setOwner(table.getOwner());
        item.setAccessCount(count == null ? 0L : count);
        item.setLastAccessTime(lastAccess);
        if (lastAccess != null) {
            item.setDaysSinceLastAccess((long) java.time.Duration.between(lastAccess, now).toDays());
        }
        return item;
    }

    private Long resolveClusterId(DataTable table, Long requestedClusterId) {
        if (requestedClusterId != null) {
            return requestedClusterId;
        }
        if (table.getClusterId() != null) {
            return table.getClusterId();
        }
        throw new IllegalArgumentException("未指定 clusterId，且表未绑定 clusterId");
    }

    private Map<String, Long> loadQueryStatsByDatabase(Long clusterId, String database) {
        Map<String, Long> result = new HashMap<>();
        String sql = "SHOW QUERY STATS FOR " + wrapIdentifier(database);
        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int tableIdx = findColumn(md, "TableName", "table_name", "table", "tablename");
            int countIdx = findColumn(md, "QueryCount", "query_count", "querycount", "count");
            if (tableIdx < 1) {
                tableIdx = 1;
            }
            if (countIdx < 1) {
                countIdx = 2;
            }
            while (rs.next()) {
                String tableName = normalizeIdentifier(rs.getString(tableIdx));
                if (!StringUtils.hasText(tableName)) {
                    continue;
                }
                result.put(tableName, parseLongValue(rs.getObject(countIdx)));
            }
        } catch (Exception e) {
            log.warn("SHOW QUERY STATS failed for cluster={} db={}, reason={}", clusterId, database, e.getMessage());
        }
        return result;
    }

    private Optional<AuditSource> resolveAuditSource(Long clusterId) {
        CachedAuditSource cached = auditSourceCache.get(clusterId);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.cachedAt) <= AUDIT_SOURCE_CACHE_MILLIS) {
            return Optional.ofNullable(cached.source);
        }

        AuditSource source = null;
        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                Statement stmt = connection.createStatement()) {
            List<String[]> candidates = Arrays.asList(
                    new String[] { "__internal_schema", "audit_log" },
                    new String[] { "doris_audit_db__", "doris_audit_tbl__" });

            for (String[] candidate : candidates) {
                String sql = "SELECT * FROM " + wrapTable(candidate[0], candidate[1]) + " LIMIT 1";
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    source = buildAuditSource(candidate[0], candidate[1], md);
                    if (source != null) {
                        break;
                    }
                } catch (SQLException ignore) {
                    // try next candidate
                }
            }
        } catch (Exception e) {
            log.debug("resolveAuditSource failed for cluster={}, reason={}", clusterId, e.getMessage());
        }

        auditSourceCache.put(clusterId, new CachedAuditSource(source, now));
        return Optional.ofNullable(source);
    }

    private AuditSource buildAuditSource(String db, String table, ResultSetMetaData md) throws SQLException {
        Map<String, String> cols = new HashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            if (!StringUtils.hasText(label)) {
                label = md.getColumnName(i);
            }
            cols.put(label.toLowerCase(Locale.ROOT), label);
        }

        String timeCol = firstPresent(cols, "time", "event_time", "log_time");
        String dbCol = firstPresent(cols, "db", "database", "db_name");
        String stmtCol = firstPresent(cols, "stmt", "statement", "sql");
        if (!StringUtils.hasText(timeCol) || !StringUtils.hasText(dbCol) || !StringUtils.hasText(stmtCol)) {
            return null;
        }

        String userCol = firstPresent(cols, "user", "qualified_user", "username");
        String durationCol = firstPresent(cols, "query_time", "query_time_ms", "latency_ms");
        return new AuditSource(db, table, timeCol, dbCol, stmtCol, userCol, durationCol);
    }

    private List<AuditEntry> queryAuditEntries(Long clusterId, AuditSource source, LocalDateTime startTime) {
        List<AuditEntry> entries = new ArrayList<>();
        String selectUser = StringUtils.hasText(source.userColumn) ? wrapIdentifier(source.userColumn) : "NULL";
        String selectDuration = StringUtils.hasText(source.durationColumn) ? wrapIdentifier(source.durationColumn) : "NULL";
        String sql = "SELECT "
                + wrapIdentifier(source.timeColumn) + " AS t, "
                + wrapIdentifier(source.dbColumn) + " AS d, "
                + wrapIdentifier(source.stmtColumn) + " AS s, "
                + selectUser + " AS u, "
                + selectDuration + " AS q "
                + "FROM " + source.qualifiedName()
                + " WHERE " + wrapIdentifier(source.timeColumn) + " >= ? "
                + "ORDER BY " + wrapIdentifier(source.timeColumn) + " DESC "
                + "LIMIT " + MAX_AUDIT_SCAN_ROWS;
        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(startTime));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    AuditEntry entry = new AuditEntry();
                    entry.setTime(parseLocalDateTime(rs.getObject("t")));
                    entry.setDatabaseName(normalizeIdentifier(rs.getString("d")));
                    entry.setStmt(rs.getString("s"));
                    entry.setUser(rs.getString("u"));
                    entry.setQueryTimeMs(parseNullableLong(rs.getObject("q")));
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("queryAuditEntries failed, source={}, cluster={}, reason={}",
                    source.qualifiedName(), clusterId, e.getMessage());
        }
        return entries;
    }

    private Set<String> extractIdentifiers(String sql, String defaultDb) {
        Set<String> refs = new HashSet<>();
        if (!StringUtils.hasText(sql)) {
            return refs;
        }
        collectRefs(refs, sql, defaultDb, FROM_JOIN_PATTERN);
        collectRefs(refs, sql, defaultDb, INSERT_INTO_PATTERN);
        collectRefs(refs, sql, defaultDb, UPDATE_PATTERN);
        collectRefs(refs, sql, defaultDb, DELETE_FROM_PATTERN);
        return refs;
    }

    private void collectRefs(Set<String> collector, String sql, String defaultDb, Pattern pattern) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String db = normalizeIdentifier(matcher.group(1));
            String table = normalizeIdentifier(matcher.group(2));
            if (!StringUtils.hasText(table)) {
                continue;
            }
            String resolvedDb = StringUtils.hasText(db) ? db : normalizeIdentifier(defaultDb);
            if (!StringUtils.hasText(resolvedDb)) {
                continue;
            }
            collector.add(buildIdentifier(resolvedDb, table));
        }
    }

    private String buildIdentifier(String db, String table) {
        return normalizeIdentifier(db) + "." + normalizeIdentifier(table);
    }

    private String buildClusterIdentifier(Long clusterId, String db, String table) {
        return clusterId + "::" + buildIdentifier(db, table);
    }

    private String buildClusterIdentifier(Long clusterId, String dbAndTable) {
        return clusterId + "::" + normalizeIdentifier(dbAndTable);
    }

    private int findColumn(ResultSetMetaData md, String... candidates) throws SQLException {
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            if (!StringUtils.hasText(label)) {
                label = md.getColumnName(i);
            }
            for (String candidate : candidates) {
                if (candidate.equalsIgnoreCase(label)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String firstPresent(Map<String, String> columns, String... candidates) {
        for (String candidate : candidates) {
            String found = columns.get(candidate.toLowerCase(Locale.ROOT));
            if (StringUtils.hasText(found)) {
                return found;
            }
        }
        return null;
    }

    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime()).toLocalDateTime();
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof String) {
            try {
                return Timestamp.valueOf((String) value).toLocalDateTime();
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private Long parseNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private long parseLongValue(Object value) {
        Long parsed = parseNullableLong(value);
        return parsed == null ? 0L : parsed;
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    private String extractTableName(String fullName) {
        if (!StringUtils.hasText(fullName)) {
            return fullName;
        }
        String cleaned = fullName.replace("`", "").trim();
        int idx = cleaned.indexOf('.');
        return idx >= 0 && idx < cleaned.length() - 1 ? cleaned.substring(idx + 1) : cleaned;
    }

    private String wrapIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String wrapTable(String database, String table) {
        return wrapIdentifier(database) + "." + wrapIdentifier(table);
    }

    private static class CachedAuditSource {
        private final AuditSource source;
        private final long cachedAt;

        private CachedAuditSource(AuditSource source, long cachedAt) {
            this.source = source;
            this.cachedAt = cachedAt;
        }
    }

    private static class AuditSource {
        private final String database;
        private final String table;
        private final String timeColumn;
        private final String dbColumn;
        private final String stmtColumn;
        private final String userColumn;
        private final String durationColumn;

        private AuditSource(String database, String table, String timeColumn, String dbColumn, String stmtColumn,
                String userColumn, String durationColumn) {
            this.database = database;
            this.table = table;
            this.timeColumn = timeColumn;
            this.dbColumn = dbColumn;
            this.stmtColumn = stmtColumn;
            this.userColumn = userColumn;
            this.durationColumn = durationColumn;
        }

        private String qualifiedName() {
            return "`" + database + "`.`" + table + "`";
        }
    }

    private static class AuditEntry {
        private LocalDateTime time;
        private String databaseName;
        private String stmt;
        private String user;
        private Long queryTimeMs;

        public LocalDateTime getTime() {
            return time;
        }

        public void setTime(LocalDateTime time) {
            this.time = time;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getStmt() {
            return stmt;
        }

        public void setStmt(String stmt) {
            this.stmt = stmt;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public Long getQueryTimeMs() {
            return queryTimeMs;
        }

        public void setQueryTimeMs(Long queryTimeMs) {
            this.queryTimeMs = queryTimeMs;
        }
    }
}
