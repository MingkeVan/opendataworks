package com.onedata.portal.controller;

import com.onedata.portal.dto.PermissionGrantRequest;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.service.PermissionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 权限管理Controller
 * 提供权限分配、撤销、查询等管理接口
 */
@Slf4j
@RestController
@RequestMapping("/v1/permissions")
@RequiredArgsConstructor
public class PermissionManagementController {

    private final PermissionManagementService permissionManagementService;

    /**
     * 授予用户数据库权限
     */
    @PostMapping("/grant")
    public Result<Void> grantPermission(@RequestBody PermissionGrantRequest request) {
        // TODO: 从当前登录用户获取grantedBy
        String grantedBy = "admin"; // 临时硬编码，后续从用户上下文获取

        permissionManagementService.grantPermission(
                request.getUserId(),
                request.getClusterId(),
                request.getDatabaseName(),
                request.getPermissionLevel(),
                grantedBy,
                request.getExpiresAt()
        );

        return Result.success();
    }

    /**
     * 批量授予权限
     */
    @PostMapping("/grant/batch")
    public Result<Void> batchGrantPermissions(@RequestBody PermissionGrantRequest request) {
        // TODO: 从当前登录用户获取grantedBy
        String grantedBy = "admin";

        permissionManagementService.batchGrantPermissions(
                request.getUserId(),
                request.getClusterId(),
                request.getDatabaseNames(),
                request.getPermissionLevel(),
                grantedBy
        );

        return Result.success();
    }

    /**
     * 撤销用户数据库权限
     */
    @DeleteMapping("/revoke")
    public Result<Void> revokePermission(
            @RequestParam String userId,
            @RequestParam Long clusterId,
            @RequestParam String databaseName) {

        permissionManagementService.revokePermission(userId, clusterId, databaseName);
        return Result.success();
    }

    /**
     * 批量撤销权限
     */
    @PostMapping("/revoke/batch")
    public Result<Void> batchRevokePermissions(@RequestBody PermissionGrantRequest request) {
        permissionManagementService.batchRevokePermissions(
                request.getUserId(),
                request.getClusterId(),
                request.getDatabaseNames()
        );

        return Result.success();
    }

    /**
     * 获取用户的所有权限
     */
    @GetMapping("/user/{userId}")
    public Result<List<UserDatabasePermission>> getUserPermissions(@PathVariable String userId) {
        List<UserDatabasePermission> permissions = permissionManagementService.getUserPermissions(userId);
        return Result.success(permissions);
    }

    /**
     * 获取用户在指定集群上的权限
     */
    @GetMapping("/user/{userId}/cluster/{clusterId}")
    public Result<List<UserDatabasePermission>> getUserPermissionsByCluster(
            @PathVariable String userId,
            @PathVariable Long clusterId) {

        List<UserDatabasePermission> permissions = 
                permissionManagementService.getUserPermissionsByCluster(userId, clusterId);
        return Result.success(permissions);
    }

    /**
     * 获取用户可访问的数据库列表
     */
    @GetMapping("/user/{userId}/cluster/{clusterId}/databases")
    public Result<List<String>> getAccessibleDatabases(
            @PathVariable String userId,
            @PathVariable Long clusterId) {

        List<String> databases = permissionManagementService.getAccessibleDatabases(userId, clusterId);
        return Result.success(databases);
    }

    /**
     * 获取数据库的所有用户权限
     */
    @GetMapping("/database")
    public Result<List<UserDatabasePermission>> getDatabasePermissions(
            @RequestParam Long clusterId,
            @RequestParam String databaseName) {

        List<UserDatabasePermission> permissions = 
                permissionManagementService.getDatabasePermissions(clusterId, databaseName);
        return Result.success(permissions);
    }
}
