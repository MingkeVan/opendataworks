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

    private String feHost;

    private Integer fePort;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Integer isDefault;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
