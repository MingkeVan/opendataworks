package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.dto.backup.SchemaBackupConfigRequest;
import com.onedata.portal.dto.backup.SchemaBackupItem;
import com.onedata.portal.dto.backup.SchemaBackupRestoreRequest;
import com.onedata.portal.dto.backup.SchemaBackupRestoreResponse;
import com.onedata.portal.dto.backup.SchemaBackupSnapshot;
import com.onedata.portal.dto.backup.SchemaBackupTriggerResponse;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import com.onedata.portal.service.SchemaBackupService;
import com.onedata.portal.util.TableNameUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Doris 集群管理 Controller
 */
@RestController
@RequestMapping("/v1/doris-clusters")
@RequiredArgsConstructor
public class DorisClusterController {

    private final DorisClusterService dorisClusterService;
    private final DorisConnectionService dorisConnectionService;
    private final MetadataSyncHistoryService metadataSyncHistoryService;
    private final SchemaBackupService schemaBackupService;

    @GetMapping
    public Result<List<DorisCluster>> list() {
        return Result.success(dorisClusterService.listAll());
    }

    @GetMapping("/{id}")
    public Result<DorisCluster> getById(@PathVariable Long id) {
        return Result.success(dorisClusterService.getById(id));
    }

    @PostMapping
    public Result<DorisCluster> create(@RequestBody DorisCluster cluster) {
        return Result.success(dorisClusterService.create(cluster));
    }

    @PutMapping("/{id}")
    public Result<DorisCluster> update(@PathVariable Long id, @RequestBody DorisCluster cluster) {
        return Result.success(dorisClusterService.update(id, cluster));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dorisClusterService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        dorisClusterService.setDefault(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        return Result.success(dorisConnectionService.testConnection(id));
    }

    @RequireAuth
    @GetMapping("/{id}/databases")
    public Result<List<String>> listDatabases(@PathVariable Long id) {
        return Result.success(dorisConnectionService.getAllDatabases(id));
    }

    @RequireAuth
    @GetMapping("/{id}/databases/{database}/tables")
    public Result<List<Map<String, Object>>> listTables(
            @PathVariable Long id,
            @PathVariable String database,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeprecated) {
        List<Map<String, Object>> tables = dorisConnectionService.getTablesInDatabase(id, database);
        if (includeDeprecated) {
            return Result.success(tables);
        }
        List<Map<String, Object>> filtered = tables.stream()
                .filter(table -> {
                    Object tableName = table.get("tableName");
                    return tableName == null || !TableNameUtils.isDeprecatedTableName(String.valueOf(tableName));
                })
                .collect(Collectors.toList());
        return Result.success(filtered);
    }

    @GetMapping("/{id}/sync-history")
    public Result<PageResult<MetadataSyncHistory>> listSyncHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType) {
        DorisCluster cluster = dorisClusterService.getById(id);
        if (cluster == null) {
            return Result.fail("数据源不存在");
        }
        Page<MetadataSyncHistory> page = metadataSyncHistoryService.listByCluster(id, pageNum, pageSize, status, triggerType);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}/sync-history/{runId}")
    public Result<MetadataSyncHistory> getSyncHistoryDetail(
            @PathVariable Long id,
            @PathVariable Long runId) {
        MetadataSyncHistory history = metadataSyncHistoryService.getById(runId);
        if (history == null || !Objects.equals(history.getClusterId(), id)) {
            return Result.fail("同步记录不存在");
        }
        return Result.success(history);
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups")
    public Result<List<SchemaBackupItem>> listSchemaBackups(@PathVariable Long id) {
        return Result.success(schemaBackupService.listSchemaBackupItems(id));
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups/{schema}")
    public Result<SchemaBackupItem> getSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.getSchemaBackupItem(id, schema));
    }

    @RequireAuth
    @PutMapping("/{id}/schema-backups/{schema}")
    public Result<SchemaBackupItem> saveSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema,
            @RequestBody SchemaBackupConfigRequest request) {
        return Result.success(schemaBackupService.upsertConfig(id, schema, request));
    }

    @RequireAuth
    @PostMapping("/{id}/schema-backups/{schema}/backup")
    public Result<SchemaBackupTriggerResponse> triggerSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.triggerBackup(id, schema, "manual"));
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups/{schema}/snapshots")
    public Result<List<SchemaBackupSnapshot>> listSnapshots(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.listSnapshots(id, schema));
    }

    @RequireAuth
    @PostMapping("/{id}/schema-backups/{schema}/restore")
    public Result<SchemaBackupRestoreResponse> restoreSnapshot(
            @PathVariable Long id,
            @PathVariable String schema,
            @RequestBody SchemaBackupRestoreRequest request) {
        return Result.success(schemaBackupService.restoreSnapshot(id, schema, request));
    }
}
