package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户数据库权限实体
 */
@Data
@TableName("user_database_permissions")
public class UserDatabasePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private Long clusterId;

    private String databaseName;

    private String permissionLevel; // readonly, readwrite

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime grantedAt;

    private String grantedBy;

    private LocalDateTime expiresAt;
}
