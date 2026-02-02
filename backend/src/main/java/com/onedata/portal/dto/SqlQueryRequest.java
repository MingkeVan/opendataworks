package com.onedata.portal.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * SQL 查询请求
 */
@Data
public class SqlQueryRequest {

    /**
     * 客户端查询ID（用于 Stop/终止执行）
     * 建议传入 DataStudio 的 tabId
     */
    private String clientQueryId;

    /**
     * Doris 集群ID，可为空表示使用默认集群
     */
    private Long clusterId;

    /**
     * 目标数据库（必填）
     */
    @NotBlank(message = "数据库不能为空")
    private String database;

    /**
     * 查询 SQL，仅允许只读语句
     */
    @NotBlank(message = "SQL 不能为空")
    private String sql;

    /**
     * 返回数据的最大行数
     */
    private Integer limit;
}
