package com.onedata.portal.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SQL 查询响应
 */
@Data
public class SqlQueryResponse {

    /**
     * 列名
     */
    private List<String> columns;

    /**
     * 数据行（预览数据）
     */
    private List<Map<String, Object>> rows;

    /**
     * 返回的行数
     */
    private Integer previewRowCount;

    /**
     * 是否还有更多数据未返回
     */
    private boolean hasMore;

    /**
     * 查询耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 历史记录ID
     */
    private Long historyId;

    /**
     * 执行时间
     */
    private LocalDateTime executedAt;
}
