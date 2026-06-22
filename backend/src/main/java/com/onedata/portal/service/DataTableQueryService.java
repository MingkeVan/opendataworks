package com.onedata.portal.service;

import com.onedata.portal.dto.TableAccessStats;
import com.onedata.portal.dto.TableExport;
import com.onedata.portal.dto.TableLocation;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableStatisticsHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据表只读查询编排服务。
 *
 * <p>承接控制器下沉的 Doris 只读查询编排：统计信息（含缓存与历史落库）、访问统计、
 * DDL、数据预览与导出、统计历史。库表标识统一经 {@link DataTableService#requireTableLocation}
 * 解析。失败以「最终态消息」异常表达，由控制器薄 {@code try/catch} 映射为 {@code Result}。
 */
@Service
@RequiredArgsConstructor
public class DataTableQueryService {

    private final DataTableService dataTableService;
    private final DorisConnectionService dorisConnectionService;
    private final TableStatisticsCacheService cacheService;
    private final TableStatisticsHistoryService historyService;
    private final DataExportService dataExportService;
    private final DorisTableAccessService dorisTableAccessService;

    /**
     * 获取表在 Doris 中的统计信息（支持缓存，forceRefresh 强制刷新）。
     */
    public TableStatistics getStatistics(Long id, Long clusterId, boolean forceRefresh) {
        // 如果不强制刷新，先尝试从缓存获取
        if (!forceRefresh) {
            TableStatistics cached = cacheService.get(id, clusterId);
            if (cached != null) {
                return cached;
            }
        }

        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }

        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            TableStatistics statistics = dorisConnectionService.getTableStatistics(
                    clusterId, location.getDatabase(), location.getTableName());

            // 放入缓存
            cacheService.put(id, clusterId, statistics);

            // 保存到历史记录
            historyService.saveHistory(id, clusterId, statistics);

            return statistics;
        } catch (Exception e) {
            throw new RuntimeException("获取表统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取表访问统计（Doris 层面）。
     */
    public TableAccessStats getAccessStats(Long id, Long clusterId, Integer recentDays, Integer trendDays,
            Integer topUsers) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        try {
            return dorisTableAccessService.getTableAccessStats(
                    table,
                    clusterId,
                    recentDays == null ? 30 : recentDays,
                    trendDays == null ? 14 : trendDays,
                    topUsers == null ? 5 : topUsers);
        } catch (Exception e) {
            throw new RuntimeException("获取表访问统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取数据库中所有表的统计信息。
     */
    public List<TableStatistics> getDatabaseStatistics(String database, Long clusterId) {
        try {
            return dorisConnectionService.getAllTableStatistics(clusterId, database);
        } catch (Exception e) {
            throw new RuntimeException("获取数据库表统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取表的统计历史记录（最近 N 条）。
     */
    public List<TableStatisticsHistory> getStatisticsHistory(Long id, int limit) {
        try {
            return historyService.getRecentHistory(id, limit);
        } catch (Exception e) {
            throw new RuntimeException("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表最近 7 天的统计历史。
     */
    public List<TableStatisticsHistory> getLast7DaysHistory(Long id) {
        try {
            return historyService.getLast7DaysHistory(id);
        } catch (Exception e) {
            throw new RuntimeException("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表最近 30 天的统计历史。
     */
    public List<TableStatisticsHistory> getLast30DaysHistory(Long id) {
        try {
            return historyService.getLast30DaysHistory(id);
        } catch (Exception e) {
            throw new RuntimeException("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表的建表语句（DDL）。
     */
    public String getTableDdl(Long id, Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            return dorisConnectionService.getTableDdl(clusterId, location.getDatabase(), location.getTableName());
        } catch (Exception e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage());
        }
    }

    /**
     * 根据数据库与表名获取表的建表语句（DDL）。
     */
    public String getTableDdlByName(Long clusterId, String database, String tableName) {
        if (clusterId == null) {
            throw new RuntimeException("请指定数据源");
        }
        if (!StringUtils.hasText(database) || !StringUtils.hasText(tableName)) {
            throw new RuntimeException("数据库和表名不能为空");
        }
        String actualTableName = dataTableService.extractActualTableName(database, tableName);
        if (!StringUtils.hasText(actualTableName)) {
            throw new RuntimeException("表名无效");
        }
        try {
            return dorisConnectionService.getTableDdl(clusterId, database, actualTableName);
        } catch (Exception e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage());
        }
    }

    /**
     * 预览表数据。
     */
    public List<Map<String, Object>> previewTableData(Long id, Long clusterId, int limit) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            return dorisConnectionService.previewTableData(
                    clusterId, location.getDatabase(), location.getTableName(), limit);
        } catch (Exception e) {
            throw new RuntimeException("预览表数据失败: " + e.getMessage());
        }
    }

    /**
     * 产出表数据导出字节与 Content-Type；文件名与响应头由控制器组装。
     */
    public TableExport export(TableLocation location, Long clusterId, String format, int limit) throws IOException {
        String database = location.getDatabase();
        String actualTableName = location.getTableName();

        byte[] data;
        String contentType;
        String fileExtension;

        switch (format.toLowerCase()) {
            case "excel":
            case "xlsx":
                data = dataExportService.exportToExcel(clusterId, database, actualTableName, limit);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                fileExtension = "xlsx";
                break;
            case "json":
                data = dataExportService.exportToJson(clusterId, database, actualTableName, limit);
                contentType = "application/json";
                fileExtension = "json";
                break;
            case "csv":
            default:
                data = dataExportService.exportToCsv(clusterId, database, actualTableName, limit);
                contentType = "text/csv;charset=UTF-8";
                fileExtension = "csv";
                break;
        }

        return new TableExport(data, contentType, fileExtension, actualTableName);
    }
}
