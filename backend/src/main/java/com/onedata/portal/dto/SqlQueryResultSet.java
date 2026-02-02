package com.onedata.portal.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单个 SQL 结果集
 */
@Data
public class SqlQueryResultSet {

    /**
     * 结果集序号（从 1 开始）
     */
    private Integer index;

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
}

