package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Doris 连接服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisConnectionService {

    private static final String JDBC_TEMPLATE = "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_DB = "information_schema";

    private final DorisClusterMapper dorisClusterMapper;

    /**
     * 执行 SQL (主要用于创建表等 DDL)
     */
    public void execute(Long clusterId, String sql) {
        DorisCluster cluster = resolveCluster(clusterId);
        try (Connection connection = getConnection(cluster, null);
             Statement statement = connection.createStatement()) {
            log.info("Executing SQL on Doris cluster {}: {}", cluster.getClusterName(), abbreviate(sql));
            statement.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL on Doris cluster {}", cluster.getClusterName(), e);
            throw new RuntimeException("执行 Doris SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试连接
     */
    public boolean testConnection(Long clusterId) {
        DorisCluster cluster = resolveCluster(clusterId);
        try (Connection ignored = getConnection(cluster, null)) {
            return true;
        } catch (SQLException e) {
            log.warn("Doris connection test failed for cluster {}", cluster.getClusterName(), e);
            return false;
        }
    }

    /**
     * 获取连接
     */
    public Connection getConnection(Long clusterId) throws SQLException {
        DorisCluster cluster = resolveCluster(clusterId);
        return getConnection(cluster, null);
    }

    /**
     * 获取连接并指定数据库
     */
    public Connection getConnection(Long clusterId, String database) throws SQLException {
        DorisCluster cluster = resolveCluster(clusterId);
        return getConnection(cluster, database);
    }

    private Connection getConnection(DorisCluster cluster, String database) throws SQLException {
        String targetDb = StringUtils.hasText(database) ? database : DEFAULT_DB;
        String url = String.format(JDBC_TEMPLATE, cluster.getFeHost(), cluster.getFePort(), targetDb);
        String password = cluster.getPassword() == null ? "" : cluster.getPassword();
        Connection connection = DriverManager.getConnection(url, cluster.getUsername(), password);
        applySessionCharset(connection);
        return connection;
    }

    private DorisCluster resolveCluster(Long clusterId) {
        DorisCluster cluster;
        if (clusterId != null) {
            cluster = dorisClusterMapper.selectById(clusterId);
            if (cluster == null) {
                throw new RuntimeException("未找到指定的 Doris 集群: " + clusterId);
            }
        } else {
            cluster = dorisClusterMapper.selectOne(
                new LambdaQueryWrapper<DorisCluster>()
                    .eq(DorisCluster::getIsDefault, 1)
                    .eq(DorisCluster::getStatus, "active")
                    .last("LIMIT 1")
            );
            if (cluster == null) {
                throw new RuntimeException("未配置默认的 Doris 集群");
            }
        }
        return cluster;
    }

    private void applySessionCharset(Connection connection) {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET NAMES utf8mb4");
        } catch (SQLException primaryEx) {
            log.warn("Doris does not support utf8mb4 charset, fallback to utf8. reason={}", primaryEx.getMessage());
            try (Statement fallback = connection.createStatement()) {
                fallback.execute("SET NAMES utf8");
            } catch (SQLException secondaryEx) {
                log.warn("Failed to set Doris session charset to utf8. reason={}", secondaryEx.getMessage());
            }
        }
    }

    private String abbreviate(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        String trimmed = sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    /**
     * 获取表统计信息
     */
    public TableStatistics getTableStatistics(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);

        String sql = "SELECT " +
                "TABLE_SCHEMA, " +
                "TABLE_NAME, " +
                "TABLE_TYPE, " +
                "TABLE_COMMENT, " +
                "CREATE_TIME, " +
                "UPDATE_TIME, " +
                "TABLE_ROWS, " +
                "DATA_LENGTH, " +
                "ENGINE " +
                "FROM information_schema.tables " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection connection = getConnection(cluster, null);
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    TableStatistics stats = new TableStatistics();
                    stats.setDatabaseName(rs.getString("TABLE_SCHEMA"));
                    stats.setTableName(rs.getString("TABLE_NAME"));
                    stats.setTableType(rs.getString("TABLE_TYPE"));
                    stats.setTableComment(rs.getString("TABLE_COMMENT"));

                    Timestamp createTimestamp = rs.getTimestamp("CREATE_TIME");
                    if (createTimestamp != null) {
                        stats.setCreateTime(createTimestamp.toLocalDateTime());
                    }

                    Timestamp updateTimestamp = rs.getTimestamp("UPDATE_TIME");
                    if (updateTimestamp != null) {
                        stats.setLastUpdateTime(updateTimestamp.toLocalDateTime());
                    }

                    stats.setRowCount(rs.getLong("TABLE_ROWS"));

                    long dataSize = rs.getLong("DATA_LENGTH");
                    stats.setDataSize(dataSize);
                    stats.setDataSizeReadable(formatBytes(dataSize));

                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setAvailable(true);
                    stats.setLastCheckTime(LocalDateTime.now());

                    // 获取分区和副本信息
                    enrichTableDetails(connection, database, tableName, stats);

                    return stats;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get table statistics for {}.{}", database, tableName, e);
            throw new RuntimeException("获取表统计信息失败: " + e.getMessage(), e);
        }

        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }

    /**
     * 获取所有表的统计信息
     */
    public List<TableStatistics> getAllTableStatistics(Long clusterId, String database) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<TableStatistics> result = new ArrayList<>();

        String sql = "SELECT " +
                "TABLE_SCHEMA, " +
                "TABLE_NAME, " +
                "TABLE_TYPE, " +
                "TABLE_COMMENT, " +
                "CREATE_TIME, " +
                "UPDATE_TIME, " +
                "TABLE_ROWS, " +
                "DATA_LENGTH, " +
                "ENGINE " +
                "FROM information_schema.tables " +
                "WHERE TABLE_SCHEMA = ? " +
                "ORDER BY TABLE_NAME";

        try (Connection connection = getConnection(cluster, null);
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableStatistics stats = new TableStatistics();
                    stats.setDatabaseName(rs.getString("TABLE_SCHEMA"));
                    stats.setTableName(rs.getString("TABLE_NAME"));
                    stats.setTableType(rs.getString("TABLE_TYPE"));
                    stats.setTableComment(rs.getString("TABLE_COMMENT"));

                    Timestamp createTimestamp = rs.getTimestamp("CREATE_TIME");
                    if (createTimestamp != null) {
                        stats.setCreateTime(createTimestamp.toLocalDateTime());
                    }

                    Timestamp updateTimestamp = rs.getTimestamp("UPDATE_TIME");
                    if (updateTimestamp != null) {
                        stats.setLastUpdateTime(updateTimestamp.toLocalDateTime());
                    }

                    stats.setRowCount(rs.getLong("TABLE_ROWS"));

                    long dataSize = rs.getLong("DATA_LENGTH");
                    stats.setDataSize(dataSize);
                    stats.setDataSizeReadable(formatBytes(dataSize));

                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setAvailable(true);
                    stats.setLastCheckTime(LocalDateTime.now());

                    result.add(stats);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get all table statistics for database {}", database, e);
            throw new RuntimeException("获取数据库表统计信息失败: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 丰富表详细信息（分区数、副本数、分桶数）
     */
    private void enrichTableDetails(Connection connection, String database, String tableName, TableStatistics stats) {
        // 查询分区信息
        String partitionSql = "SELECT COUNT(*) as partition_count FROM information_schema.partitions " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement stmt = connection.prepareStatement(partitionSql)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setPartitionCount(rs.getInt("partition_count"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get partition count for {}.{}", database, tableName, e);
        }

        // 查询副本和分桶信息（从 SHOW CREATE TABLE 中解析）
        String showCreateSql = "SHOW CREATE TABLE " + database + "." + tableName;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(showCreateSql)) {
            if (rs.next()) {
                String createTableSql = rs.getString(2);

                // 解析副本数
                if (createTableSql.contains("\"replication_num\"")) {
                    int start = createTableSql.indexOf("\"replication_num\" = \"") + 21;
                    int end = createTableSql.indexOf("\"", start);
                    if (start > 20 && end > start) {
                        try {
                            stats.setReplicationNum(Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse replication_num", e);
                        }
                    }
                }

                // 解析分桶数
                if (createTableSql.contains("BUCKETS ")) {
                    int start = createTableSql.indexOf("BUCKETS ") + 8;
                    int end = start;
                    while (end < createTableSql.length() && Character.isDigit(createTableSql.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        try {
                            stats.setBucketNum(Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse bucket num", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get table details from SHOW CREATE TABLE for {}.{}", database, tableName, e);
        }
    }

    /**
     * 获取表的建表语句（DDL）
     */
    public String getTableDdl(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        String showCreateSql = "SHOW CREATE TABLE `" + database + "`.`" + tableName + "`";

        try (Connection connection = getConnection(cluster, null);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(showCreateSql)) {

            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (SQLException e) {
            log.error("Failed to get DDL for {}.{}", database, tableName, e);
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }

        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }

    /**
     * 预览表数据
     */
    public List<Map<String, Object>> previewTableData(Long clusterId, String database, String tableName, int limit) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> result = new ArrayList<>();

        // 限制最大预览行数
        int maxLimit = Math.min(limit, 1000);
        String sql = "SELECT * FROM `" + database + "`.`" + tableName + "` LIMIT " + maxLimit;

        try (Connection connection = getConnection(cluster, database);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, value);
                }
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("Failed to preview data for {}.{}", database, tableName, e);
            throw new RuntimeException("预览表数据失败: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 格式化字节数为可读格式
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.2f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.2f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format("%.2f TB", tb);
    }

    /**
     * 获取所有数据库列表
     */
    public List<String> getAllDatabases(Long clusterId) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<String> databases = new ArrayList<>();

        String sql = "SHOW DATABASES";

        try (Connection connection = getConnection(cluster, null);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String dbName = rs.getString(1);
                // 过滤掉系统数据库
                if (!"information_schema".equals(dbName) && !"mysql".equals(dbName)) {
                    databases.add(dbName);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get databases from cluster {}", cluster.getClusterName(), e);
            throw new RuntimeException("获取数据库列表失败: " + e.getMessage(), e);
        }

        return databases;
    }

    /**
     * 获取指定数据库的所有表
     */
    public List<Map<String, Object>> getTablesInDatabase(Long clusterId, String database) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> tables = new ArrayList<>();

        String sql = "SELECT TABLE_NAME, TABLE_COMMENT, CREATE_TIME, UPDATE_TIME, TABLE_ROWS, DATA_LENGTH " +
                     "FROM information_schema.tables WHERE TABLE_SCHEMA = ?";

        try (Connection connection = getConnection(cluster, null);
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> table = new HashMap<>();
                    table.put("tableName", rs.getString("TABLE_NAME"));
                    table.put("tableComment", rs.getString("TABLE_COMMENT"));
                    table.put("createTime", rs.getTimestamp("CREATE_TIME"));
                    table.put("updateTime", rs.getTimestamp("UPDATE_TIME"));
                    table.put("tableRows", rs.getLong("TABLE_ROWS"));
                    table.put("dataLength", rs.getLong("DATA_LENGTH"));
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get tables from database {}", database, e);
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }

        return tables;
    }

    /**
     * 获取指定表的所有列信息
     */
    public List<Map<String, Object>> getColumnsInTable(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> columns = new ArrayList<>();

        String sql = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, ORDINAL_POSITION, COLUMN_KEY " +
                     "FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

        try (Connection connection = getConnection(cluster, null);
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> column = new HashMap<>();
                    column.put("columnName", rs.getString("COLUMN_NAME"));
                    column.put("dataType", rs.getString("DATA_TYPE"));
                    column.put("isNullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0);
                    column.put("defaultValue", rs.getString("COLUMN_DEFAULT"));
                    column.put("columnComment", rs.getString("COLUMN_COMMENT"));
                    column.put("ordinalPosition", rs.getInt("ORDINAL_POSITION"));
                    column.put("columnKey", rs.getString("COLUMN_KEY"));
                    column.put("isPrimary", "PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")) ? 1 : 0);
                    columns.add(column);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get columns from table {}.{}", database, tableName, e);
            throw new RuntimeException("获取列信息失败: " + e.getMessage(), e);
        }

        return columns;
    }

    /**
     * 获取表的详细建表信息（解析 SHOW CREATE TABLE）
     */
    public Map<String, Object> getTableCreateInfo(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        Map<String, Object> info = new HashMap<>();

        String showCreateSql = "SHOW CREATE TABLE `" + database + "`.`" + tableName + "`";

        try (Connection connection = getConnection(cluster, null);
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(showCreateSql)) {

            if (rs.next()) {
                String createTableSql = rs.getString(2);
                info.put("createTableSql", createTableSql);

                // 解析副本数
                if (createTableSql.contains("\"replication_num\"")) {
                    int start = createTableSql.indexOf("\"replication_num\" = \"") + 21;
                    int end = createTableSql.indexOf("\"", start);
                    if (start > 20 && end > start) {
                        try {
                            info.put("replicationNum", Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse replication_num", e);
                        }
                    }
                }

                // 解析分桶数
                if (createTableSql.contains("BUCKETS ")) {
                    int start = createTableSql.indexOf("BUCKETS ") + 8;
                    int end = start;
                    while (end < createTableSql.length() && Character.isDigit(createTableSql.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        try {
                            info.put("bucketNum", Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse bucket num", e);
                        }
                    }
                }

                // 解析分区字段
                if (createTableSql.contains("PARTITION BY RANGE")) {
                    int start = createTableSql.indexOf("PARTITION BY RANGE(") + 19;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 18 && end > start) {
                        String partitionField = createTableSql.substring(start, end).trim();
                        info.put("partitionField", partitionField);
                    }
                }

                // 解析分桶字段
                if (createTableSql.contains("DISTRIBUTED BY HASH")) {
                    int start = createTableSql.indexOf("DISTRIBUTED BY HASH(") + 20;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 19 && end > start) {
                        String distributionColumn = createTableSql.substring(start, end).trim();
                        info.put("distributionColumn", distributionColumn);
                    }
                }

                // 解析 Key 类型和列
                if (createTableSql.contains("UNIQUE KEY")) {
                    int start = createTableSql.indexOf("UNIQUE KEY(") + 11;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 10 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "UNIQUE");
                    }
                } else if (createTableSql.contains("AGGREGATE KEY")) {
                    int start = createTableSql.indexOf("AGGREGATE KEY(") + 14;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 13 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "AGGREGATE");
                    }
                } else if (createTableSql.contains("DUPLICATE KEY")) {
                    int start = createTableSql.indexOf("DUPLICATE KEY(") + 14;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 13 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "DUPLICATE");
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get create info for table {}.{}", database, tableName, e);
            throw new RuntimeException("获取建表信息失败: " + e.getMessage(), e);
        }

        return info;
    }
}
