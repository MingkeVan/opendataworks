package com.onedata.portal.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dashboard 表访问聚合查询投影。
 */
@Data
public class DashboardTableAccessAggregate {

    private Long clusterId;
    private String dbName;
    private String tableName;
    private Long accessCount;
    private LocalDateTime lastAccessTime;
}
