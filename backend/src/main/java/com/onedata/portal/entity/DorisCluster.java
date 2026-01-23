package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Doris 集群配置实体
 */
@Data
@TableName("doris_cluster")
public class DorisCluster {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String clusterName;

    /**
     * 数据源类型：DORIS / MYSQL
     */
    private String sourceType;

    private String feHost;

    private Integer fePort;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Integer isDefault;

    private String status;

    /**
     * 是否开启元数据自动同步（0/1）
     */
    private Integer autoSync;

    /**
     * 元数据同步 Cron 表达式
     */
    private String syncCron;

    /**
     * 最近一次自动同步时间
     */
    private LocalDateTime lastSyncTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
