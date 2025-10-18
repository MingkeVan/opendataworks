package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 血缘关系实体
 */
@Data
@TableName("data_lineage")
public class DataLineage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long upstreamTableId;

    private Long downstreamTableId;

    private String lineageType; // input, output

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
