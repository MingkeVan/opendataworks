package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Doris数据库用户配置实体
 */
@Data
@TableName("doris_database_users")
public class DorisDbUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clusterId;

    private String databaseName;

    private String readonlyUsername;

    private String readonlyPassword;

    private String readwriteUsername;

    private String readwritePassword;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
