package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dolphin_config")
public class DolphinConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String url;

    private String token;

    private String projectName;

    private String projectCode;

    private String tenantCode;

    private String workerGroup;

    private String executionType;

    private Boolean isActive;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
