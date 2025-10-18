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
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /**
     * 分页查询表列表
     */
    @GetMapping
    public Result<PageResult<DataTable>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String keyword) {
        Page<DataTable> page = dataTableService.list(pageNum, pageSize, layer, keyword);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
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
}
