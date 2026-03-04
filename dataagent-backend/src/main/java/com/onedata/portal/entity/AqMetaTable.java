package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("aq_meta_table")
public class AqMetaTable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotVersion;

    private Long tableId;

    private Long clusterId;

    private String dbName;

    private String tableName;

    private String tableComment;

    private String payloadJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
