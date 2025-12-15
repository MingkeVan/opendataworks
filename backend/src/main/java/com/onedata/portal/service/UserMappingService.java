package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.entity.DorisDbUser;
import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.mapper.DorisDbUserMapper;
import com.onedata.portal.mapper.UserDatabasePermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户映射服务
 * 负责将平台用户映射到对应的Doris用户凭据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMappingService {

    private final UserDatabasePermissionMapper userDatabasePermissionMapper;
    private final DorisDbUserMapper dorisDbUserMapper;

    /**
     * 获取用户对指定数据库的Doris凭据
     *
     * @param userId       用户ID
     * @param clusterId    集群ID
     * @param databaseName 数据库名
     * @return Doris用户凭据
     */
    public DorisCredential getDorisCredential(String userId, Long clusterId, String databaseName) {
        // 1. 查询用户对该数据库的权限
        UserDatabasePermission permission = userDatabasePermissionMapper.selectOne(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        );

        if (permission == null) {
            log.warn("User {} has no permission for database {} on cluster {}", userId, databaseName, clusterId);
            throw new RuntimeException("用户没有访问数据库 " + databaseName + " 的权限");
        }

        // 2. 查询该数据库的标准Doris用户配置
        DorisDbUser dbUser = dorisDbUserMapper.selectOne(
                new LambdaQueryWrapper<DorisDbUser>()
                        .eq(DorisDbUser::getClusterId, clusterId)
                        .eq(DorisDbUser::getDatabaseName, databaseName)
        );

        if (dbUser == null) {
            log.error("Doris database user config not found for database {} on cluster {}", databaseName, clusterId);
            throw new RuntimeException("数据库 " + databaseName + " 的Doris用户配置不存在");
        }

        // 3. 根据权限级别返回对应的Doris用户凭据
        if ("readonly".equals(permission.getPermissionLevel())) {
            log.debug("User {} mapped to readonly user for database {}", userId, databaseName);
            return new DorisCredential(dbUser.getReadonlyUsername(), dbUser.getReadonlyPassword());
        } else if ("readwrite".equals(permission.getPermissionLevel())) {
            log.debug("User {} mapped to readwrite user for database {}", userId, databaseName);
            return new DorisCredential(dbUser.getReadwriteUsername(), dbUser.getReadwritePassword());
        } else {
            log.error("Unknown permission level: {}", permission.getPermissionLevel());
            throw new RuntimeException("未知的权限级别: " + permission.getPermissionLevel());
        }
    }

    /**
     * 检查用户是否有数据库访问权限
     *
     * @param userId       用户ID
     * @param clusterId    集群ID
     * @param databaseName 数据库名
     * @return 是否有权限
     */
    public boolean hasPermission(String userId, Long clusterId, String databaseName) {
        return userDatabasePermissionMapper.selectCount(
                new LambdaQueryWrapper<UserDatabasePermission>()
                        .eq(UserDatabasePermission::getUserId, userId)
                        .eq(UserDatabasePermission::getClusterId, clusterId)
                        .eq(UserDatabasePermission::getDatabaseName, databaseName)
        ) > 0;
    }
}
