package com.onedata.portal.service;

import com.onedata.portal.entity.UserDatabasePermission;
import com.onedata.portal.mapper.UserDatabasePermissionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PermissionManagementService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class PermissionManagementServiceTest {

    @Mock
    private UserDatabasePermissionMapper userDatabasePermissionMapper;

    @InjectMocks
    private PermissionManagementService permissionManagementService;

    @Test
    void testGrantPermission_NewPermission() {
        // Given: 用户没有现有权限
        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(null);
        when(userDatabasePermissionMapper.insert(any())).thenReturn(1);

        // When: 授予权限
        permissionManagementService.grantPermission(
                "user123", 1L, "test_db", "readonly", "admin", null);

        // Then: 应该插入新权限
        ArgumentCaptor<UserDatabasePermission> captor = ArgumentCaptor.forClass(UserDatabasePermission.class);
        verify(userDatabasePermissionMapper).insert(captor.capture());

        UserDatabasePermission captured = captor.getValue();
        assertEquals("user123", captured.getUserId());
        assertEquals(1L, captured.getClusterId());
        assertEquals("test_db", captured.getDatabaseName());
        assertEquals("readonly", captured.getPermissionLevel());
        assertEquals("admin", captured.getGrantedBy());
    }

    @Test
    void testGrantPermission_UpdateExisting() {
        // Given: 用户已有权限
        UserDatabasePermission existing = new UserDatabasePermission();
        existing.setId(1L);
        existing.setUserId("user123");
        existing.setClusterId(1L);
        existing.setDatabaseName("test_db");
        existing.setPermissionLevel("readonly");

        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(existing);
        when(userDatabasePermissionMapper.updateById(any())).thenReturn(1);

        // When: 更新权限为readwrite
        permissionManagementService.grantPermission(
                "user123", 1L, "test_db", "readwrite", "admin", null);

        // Then: 应该更新现有权限
        ArgumentCaptor<UserDatabasePermission> captor = ArgumentCaptor.forClass(UserDatabasePermission.class);
        verify(userDatabasePermissionMapper).updateById(captor.capture());

        UserDatabasePermission captured = captor.getValue();
        assertEquals("readwrite", captured.getPermissionLevel());
        assertEquals("admin", captured.getGrantedBy());
    }

    @Test
    void testRevokePermission() {
        // Given: 权限存在
        when(userDatabasePermissionMapper.delete(any())).thenReturn(1);

        // When: 撤销权限
        permissionManagementService.revokePermission("user123", 1L, "test_db");

        // Then: 应该删除权限
        verify(userDatabasePermissionMapper).delete(any());
    }

    @Test
    void testGetAccessibleDatabases() {
        // Given: 用户有多个数据库权限
        UserDatabasePermission perm1 = new UserDatabasePermission();
        perm1.setDatabaseName("db1");

        UserDatabasePermission perm2 = new UserDatabasePermission();
        perm2.setDatabaseName("db2");

        when(userDatabasePermissionMapper.selectList(any()))
                .thenReturn(Arrays.asList(perm1, perm2));

        // When: 获取可访问数据库列表
        List<String> databases = permissionManagementService.getAccessibleDatabases("user123", 1L);

        // Then: 应该返回所有数据库名
        assertEquals(2, databases.size());
        assertTrue(databases.contains("db1"));
        assertTrue(databases.contains("db2"));
    }

    @Test
    void testBatchGrantPermissions() {
        // Given: 批量授权3个数据库
        when(userDatabasePermissionMapper.selectOne(any())).thenReturn(null);
        when(userDatabasePermissionMapper.insert(any())).thenReturn(1);

        List<String> databases = Arrays.asList("db1", "db2", "db3");

        // When: 批量授权
        permissionManagementService.batchGrantPermissions(
                "user123", 1L, databases, "readonly", "admin");

        // Then: 应该插入3次
        verify(userDatabasePermissionMapper, times(3)).insert(any());
    }

    @Test
    void testBatchRevokePermissions() {
        // Given: 批量撤销3个数据库权限
        when(userDatabasePermissionMapper.delete(any())).thenReturn(1);

        List<String> databases = Arrays.asList("db1", "db2", "db3");

        // When: 批量撤销
        permissionManagementService.batchRevokePermissions("user123", 1L, databases);

        // Then: 应该删除3次
        verify(userDatabasePermissionMapper, times(3)).delete(any());
    }
}
