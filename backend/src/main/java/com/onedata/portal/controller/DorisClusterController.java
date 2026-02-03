package com.onedata.portal.controller;

import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.util.TableNameUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
}
