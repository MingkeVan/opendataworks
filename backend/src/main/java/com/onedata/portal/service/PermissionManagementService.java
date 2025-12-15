package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.mapper.UserDatabasePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 权限管理服务
 * 提供权限分配、撤销、查询等管理功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionManagementService {

    private final UserDatabasePermissionMapper userDatabasePermissionMapper;

    /**
     * 授予用户数据库权限
     *
     * @param userId          用户ID
     * @param clusterId       集群ID
     * @param databaseName    数据库名
     * @param permissionLevel 权限级别 (readonly/readwrite)
     * @param grantedBy       授权人
     * @param expiresAt       过期时间（可选）
     */
    @Transactional
    public void grantPermission(String userId, Long clusterId, String databaseName,
                                String permissionLevel, String grantedBy, LocalDateTime expiresAt) {
        // 检查是否已存在权限
        UserDatabasePermission existing = userDatabasePermissionMapper.selectOne(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        );

        if (existing != null) {
            // 更新现有权限
            existing.setPermissionLevel(permissionLevel);
            existing.setGrantedBy(grantedBy);
            existing.setExpiresAt(expiresAt);
            userDatabasePermissionMapper.updateById(existing);
            log.info("Updated permission for user {} on database {} to {}", userId, databaseName, permissionLevel);
        } else {
            // 创建新权限
            UserDatabasePermission permission = new UserDatabasePermission();
            permission.setUserId(userId);
            permission.setClusterId(clusterId);
            permission.setDatabaseName(databaseName);
            permission.setPermissionLevel(permissionLevel);
            permission.setGrantedBy(grantedBy);
            permission.setExpiresAt(expiresAt);
            userDatabasePermissionMapper.insert(permission);
            log.info("Granted {} permission to user {} on database {}", permissionLevel, userId, databaseName);
        }
    }

    /**
     * 撤销用户数据库权限
     *
     * @param userId       用户ID
     * @param clusterId    集群ID
     * @param databaseName 数据库名
     */
    @Transactional
    public void revokePermission(String userId, Long clusterId, String databaseName) {
        int deleted = userDatabasePermissionMapper.delete(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        );

        if (deleted > 0) {
            log.info("Revoked permission for user {} on database {}", userId, databaseName);
        } else {
            log.warn("No permission found to revoke for user {} on database {}", userId, databaseName);
        }
    }

    /**
     * 获取用户的所有数据库权限
     *
     * @param userId 用户ID
     * @return 权限列表
     */
    public List<UserDatabasePermission> getUserPermissions(String userId) {
        return userDatabasePermissionMapper.selectList(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
        );
    }

    /**
     * 获取用户在指定集群上的所有数据库权限
     *
     * @param userId    用户ID
     * @param clusterId 集群ID
     * @return 权限列表
     */
    public List<UserDatabasePermission> getUserPermissionsByCluster(String userId, Long clusterId) {
        return userDatabasePermissionMapper.selectList(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
        );
    }

    /**
     * 获取用户可访问的数据库列表
     *
     * @param userId    用户ID
     * @param clusterId 集群ID
     * @return 数据库名列表
     */
    public List<String> getAccessibleDatabases(String userId, Long clusterId) {
        List<UserDatabasePermission> permissions = getUserPermissionsByCluster(userId, clusterId);
        return permissions.stream()
                .map(UserDatabasePermission::getDatabaseName)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取数据库的所有用户权限
     *
     * @param clusterId    集群ID
     * @param databaseName 数据库名
     * @return 权限列表
     */
    public List<UserDatabasePermission> getDatabasePermissions(Long clusterId, String databaseName) {
        return userDatabasePermissionMapper.selectList(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        );
    }

    /**
     * 批量授予权限
     *
     * @param userId        用户ID
     * @param clusterId     集群ID
     * @param databaseNames 数据库名列表
     * @param permissionLevel 权限级别
     * @param grantedBy     授权人
     */
    @Transactional
    public void batchGrantPermissions(String userId, Long clusterId, List<String> databaseNames,
                                      String permissionLevel, String grantedBy) {
        for (String databaseName : databaseNames) {
            grantPermission(userId, clusterId, databaseName, permissionLevel, grantedBy, null);
        }
        log.info("Batch granted {} permission to user {} on {} databases", 
                permissionLevel, userId, databaseNames.size());
    }

    /**
     * 批量撤销权限
     *
     * @param userId        用户ID
     * @param clusterId     集群ID
     * @param databaseNames 数据库名列表
     */
    @Transactional
    public void batchRevokePermissions(String userId, Long clusterId, List<String> databaseNames) {
        for (String databaseName : databaseNames) {
            revokePermission(userId, clusterId, databaseName);
        }
        log.info("Batch revoked permissions for user {} on {} databases", userId, databaseNames.size());
    }
}
