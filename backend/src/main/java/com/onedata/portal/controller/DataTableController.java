package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.TableRelatedLineageResponse;
import com.onedata.portal.dto.TableRelatedTasksResponse;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.TableStatisticsCacheService;
import com.onedata.portal.service.TableStatisticsHistoryService;
import com.onedata.portal.service.DataExportService;
import com.onedata.portal.service.DorisMetadataSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 数据表管理 Controller
 */
@RestController
@RequestMapping("/v1/tables")
@RequiredArgsConstructor
public class DataTableController {

    private final DataTableService dataTableService;
    private final DorisConnectionService dorisConnectionService;
    private final TableStatisticsCacheService cacheService;
    private final TableStatisticsHistoryService historyService;
    private final DataExportService dataExportService;
    private final DorisMetadataSyncService dorisMetadataSyncService;

    /**
     * 分页查询表列表
     */
    @GetMapping
    public Result<PageResult<DataTable>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {
        Page<DataTable> page = dataTableService.list(pageNum, pageSize, layer, keyword, sortField, sortOrder);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    /**
     * 获取所有数据库列表（用于左侧导航）
     */
    @GetMapping("/databases")
    public Result<List<String>> listDatabases() {
        List<String> databases = dataTableService.listDatabases();
        return Result.success(databases);
    }

    /**
     * 根据数据库获取表列表（包含统计信息）
     */
    @GetMapping("/by-database")
    public Result<List<DataTable>> listByDatabase(
            @RequestParam String database,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {
        List<DataTable> tables = dataTableService.listByDatabase(database, sortField, sortOrder);
        return Result.success(tables);
    }

    /**
     * 获取所有表（用于下拉选择）
     */
    @GetMapping("/all")
    public Result<List<DataTable>> listAll() {
        return Result.success(dataTableService.listAll());
    }

    /**
     * 根据ID获取表详情
     */
    @GetMapping("/{id}")
    public Result<DataTable> getById(@PathVariable Long id) {
        return Result.success(dataTableService.getById(id));
    }

    /**
     * 获取表字段
     */
    @GetMapping("/{id}/fields")
    public Result<List<DataField>> getFields(@PathVariable Long id) {
        return Result.success(dataTableService.listFields(id));
    }

    /**
     * 新增字段
     */
    @PostMapping("/{id}/fields")
    public Result<DataField> createField(@PathVariable Long id, @RequestBody DataField field) {
        field.setTableId(id);
        return Result.success(dataTableService.createField(field));
    }

    /**
     * 更新字段
     */
    @PutMapping("/{id}/fields/{fieldId}")
    public Result<DataField> updateField(@PathVariable Long id, @PathVariable Long fieldId, @RequestBody DataField field) {
        field.setId(fieldId);
        field.setTableId(id);
        return Result.success(dataTableService.updateField(field));
    }

    /**
     * 删除字段
     */
    @DeleteMapping("/{id}/fields/{fieldId}")
    public Result<Void> deleteField(@PathVariable Long id, @PathVariable Long fieldId) {
        dataTableService.deleteField(fieldId);
        return Result.success();
    }

    /**
     * 获取表关联任务
     */
    @GetMapping("/{id}/tasks")
    public Result<TableRelatedTasksResponse> getRelatedTasks(@PathVariable Long id) {
        return Result.success(dataTableService.getRelatedTasks(id));
    }

    /**
     * 获取表上下游
     */
    @GetMapping("/{id}/lineage")
    public Result<TableRelatedLineageResponse> getRelatedLineage(@PathVariable Long id) {
        return Result.success(dataTableService.getRelatedLineage(id));
    }

    /**
     * 创建表
     */
    @PostMapping
    public Result<DataTable> create(@RequestBody DataTable dataTable) {
        return Result.success(dataTableService.create(dataTable));
    }

    /**
     * 更新表
     */
    @PutMapping("/{id}")
    public Result<DataTable> update(@PathVariable Long id, @RequestBody DataTable dataTable) {
        dataTable.setId(id);
        return Result.success(dataTableService.update(dataTable));
    }

    /**
     * 删除表
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataTableService.delete(id);
        return Result.success();
    }

    /**
     * 获取表在 Doris 中的统计信息
     * 支持缓存机制，默认缓存5分钟
     * 使用 forceRefresh=true 强制刷新
     */
    @GetMapping("/{id}/statistics")
    public Result<TableStatistics> getStatistics(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {

        // 如果不强制刷新，先尝试从缓存获取
        if (!forceRefresh) {
            TableStatistics cached = cacheService.get(id, clusterId);
            if (cached != null) {
                return Result.success(cached);
            }
        }

        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return Result.fail("表不存在");
        }

        // 优先使用 dbName 字段，如果不存在则从表名中解析
        String database;
        String actualTableName;

        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            // 使用 dbName 字段
            database = table.getDbName();
            // 表名可能包含数据库前缀，需要去掉
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            // 从表名中解析数据库和表名
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            // 使用默认数据库
            return Result.fail("表未配置数据库名，请先设置 dbName 字段");
        }

        try {
            TableStatistics statistics = dorisConnectionService.getTableStatistics(
                    clusterId, database, actualTableName);

            // 放入缓存
            cacheService.put(id, clusterId, statistics);

            // 保存到历史记录
            historyService.saveHistory(id, clusterId, statistics);

            return Result.success(statistics);
        } catch (Exception e) {
            return Result.fail("获取表统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取数据库中所有表的统计信息
     */
    @GetMapping("/statistics/database/{database}")
    public Result<List<TableStatistics>> getDatabaseStatistics(
            @PathVariable String database,
            @RequestParam(required = false) Long clusterId) {
        try {
            List<TableStatistics> statistics = dorisConnectionService.getAllTableStatistics(
                    clusterId, database);
            return Result.success(statistics);
        } catch (Exception e) {
            return Result.fail("获取数据库表统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取表的统计历史记录（最近N条）
     */
    @GetMapping("/{id}/statistics/history")
    public Result<List<TableStatisticsHistory>> getStatisticsHistory(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "30") int limit) {
        try {
            List<TableStatisticsHistory> history = historyService.getRecentHistory(id, limit);
            return Result.success(history);
        } catch (Exception e) {
            return Result.fail("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表最近7天的统计历史
     */
    @GetMapping("/{id}/statistics/history/last7days")
    public Result<List<TableStatisticsHistory>> getLast7DaysHistory(@PathVariable Long id) {
        try {
            List<TableStatisticsHistory> history = historyService.getLast7DaysHistory(id);
            return Result.success(history);
        } catch (Exception e) {
            return Result.fail("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表最近30天的统计历史
     */
    @GetMapping("/{id}/statistics/history/last30days")
    public Result<List<TableStatisticsHistory>> getLast30DaysHistory(@PathVariable Long id) {
        try {
            List<TableStatisticsHistory> history = historyService.getLast30DaysHistory(id);
            return Result.success(history);
        } catch (Exception e) {
            return Result.fail("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取表的建表语句（DDL）
     */
    @GetMapping("/{id}/ddl")
    public Result<String> getTableDdl(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return Result.fail("表不存在");
        }

        String database;
        String actualTableName;

        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            database = table.getDbName();
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            return Result.fail("表未配置数据库名，请先设置 dbName 字段");
        }

        try {
            String ddl = dorisConnectionService.getTableDdl(clusterId, database, actualTableName);
            return Result.success(ddl);
        } catch (Exception e) {
            return Result.fail("获取建表语句失败: " + e.getMessage());
        }
    }

    /**
     * 预览表数据
     */
    @GetMapping("/{id}/preview")
    public Result<List<Map<String, Object>>> previewTableData(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(defaultValue = "100") int limit) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return Result.fail("表不存在");
        }

        String database;
        String actualTableName;

        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            database = table.getDbName();
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            return Result.fail("表未配置数据库名，请先设置 dbName 字段");
        }

        try {
            List<Map<String, Object>> data = dorisConnectionService.previewTableData(
                    clusterId, database, actualTableName, limit);
            return Result.success(data);
        } catch (Exception e) {
            return Result.fail("预览表数据失败: " + e.getMessage());
        }
    }

    /**
     * 导出表数据
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportTableData(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "1000") int limit) {

        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return ResponseEntity.notFound().build();
        }

        String database;
        String actualTableName;

        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            database = table.getDbName();
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            return ResponseEntity.badRequest().build();
        }

        try {
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
                    contentType = "text/csv";
                    fileExtension = "csv";
                    break;
            }

            // 生成文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("%s_%s.%s", actualTableName, timestamp, fileExtension);

            // URL编码文件名以支持中文
            String encodedFilename;
            try {
                encodedFilename = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
            } catch (UnsupportedEncodingException e) {
                encodedFilename = filename;
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 稽核/比对 Doris 元数据（只检查差异，不同步）
     */
    @PostMapping("/audit-metadata")
    public Result<Map<String, Object>> auditAllMetadata(
            @RequestParam(required = false) Long clusterId) {
        try {
            DorisMetadataSyncService.AuditResult result = dorisMetadataSyncService.auditAllMetadata(clusterId);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("hasDifferences", result.hasDifferences());
            response.put("totalDifferences", result.getTotalDifferences());
            response.put("statisticsSynced", result.getStatisticsSynced());
            response.put("differences", result.getTableDifferences());
            response.put("errors", result.getErrors());
            response.put("auditTime", LocalDateTime.now());

            if (result.hasDifferences()) {
                return Result.success(response, String.format("发现 %d 处差异，请确认后同步", result.getTotalDifferences()));
            } else {
                return Result.success(response, "元数据一致，无需同步");
            }
        } catch (Exception e) {
            return Result.fail("元数据稽核失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发 Doris 元数据同步（全量同步）
     * 建议先调用 audit-metadata 接口确认差异后再调用此接口
     */
    @PostMapping("/sync-metadata")
    public Result<Map<String, Object>> syncAllMetadata(
            @RequestParam(required = false) Long clusterId) {
        try {
            DorisMetadataSyncService.SyncResult result = dorisMetadataSyncService.syncAllMetadata(clusterId);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", result.getErrors().isEmpty());
            response.put("newTables", result.getNewTables());
            response.put("updatedTables", result.getUpdatedTables());
            response.put("newFields", result.getNewFields());
            response.put("updatedFields", result.getUpdatedFields());
            response.put("deletedFields", result.getDeletedFields());
            response.put("errors", result.getErrors());
            response.put("syncTime", LocalDateTime.now());

            if (result.getErrors().isEmpty()) {
                return Result.success(response, "元数据同步成功");
            } else {
                return Result.success(response, "元数据同步完成，但存在部分错误");
            }
        } catch (Exception e) {
            return Result.fail("元数据同步失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发指定数据库的元数据同步
     */
    @PostMapping("/sync-metadata/database/{database}")
    public Result<Map<String, Object>> syncDatabaseMetadata(
            @PathVariable String database,
            @RequestParam(required = false) Long clusterId) {
        try {
            DorisMetadataSyncService.SyncResult result = dorisMetadataSyncService.syncDatabase(clusterId, database, null);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", result.getErrors().isEmpty());
            response.put("database", database);
            response.put("newTables", result.getNewTables());
            response.put("updatedTables", result.getUpdatedTables());
            response.put("newFields", result.getNewFields());
            response.put("updatedFields", result.getUpdatedFields());
            response.put("deletedFields", result.getDeletedFields());
            response.put("errors", result.getErrors());
            response.put("syncTime", LocalDateTime.now());

            if (result.getErrors().isEmpty()) {
                return Result.success(response, "数据库元数据同步成功");
            } else {
                return Result.success(response, "数据库元数据同步完成，但存在部分错误");
            }
        } catch (Exception e) {
            return Result.fail("数据库元数据同步失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发指定表的元数据同步
     */
    @PostMapping("/{id}/sync-metadata")
    public Result<Map<String, Object>> syncTableMetadata(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return Result.fail("表不存在");
        }

        String database;
        String actualTableName;

        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            database = table.getDbName();
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            return Result.fail("表未配置数据库名，请先设置 dbName 字段");
        }

        try {
            DorisMetadataSyncService.SyncResult result = dorisMetadataSyncService.syncTable(clusterId, database, actualTableName);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", result.getErrors().isEmpty());
            response.put("database", database);
            response.put("tableName", actualTableName);
            response.put("newTables", result.getNewTables());
            response.put("updatedTables", result.getUpdatedTables());
            response.put("newFields", result.getNewFields());
            response.put("updatedFields", result.getUpdatedFields());
            response.put("deletedFields", result.getDeletedFields());
            response.put("errors", result.getErrors());
            response.put("syncTime", LocalDateTime.now());

            if (result.getErrors().isEmpty()) {
                return Result.success(response, "表元数据同步成功");
            } else {
                return Result.success(response, "表元数据同步完成，但存在部分错误");
            }
        } catch (Exception e) {
            return Result.fail("表元数据同步失败: " + e.getMessage());
        }
    }
}
