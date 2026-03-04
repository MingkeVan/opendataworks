package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("aq_meta_lineage_edge")
public class AqMetaLineageEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotVersion;

    private Long lineageId;

    private Long taskId;

    private Long upstreamTableId;

    private Long downstreamTableId;

    private String lineageType;

    private String payloadJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
