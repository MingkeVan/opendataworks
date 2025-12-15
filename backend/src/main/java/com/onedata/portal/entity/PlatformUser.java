package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台用户实体
 */
@Data
@TableName("platform_users")
public class PlatformUser {

    @TableId(type = IdType.INPUT)
    private String id;

    private String oauthUserId;

    private String username;

    private String email;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
