package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台统计数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatistics {

    /**
     * 表总数
     */
    private Long totalTables;

    /**
     * 任务总数
     */
    private Long totalTasks;

    /**
     * 域总数
     */
    private Long totalDomains;

    /**
     * 执行总次数
     */
    private Long totalExecutions;

    /**
     * 成功执行次数
     */
    private Long successExecutions;

    /**
     * 失败执行次数
     */
    private Long failedExecutions;

    /**
     * 运行中执行次数
     */
    private Long runningExecutions;

    /**
     * 执行成功率（百分比）
     */
    private Double executionSuccessRate;

    /**
     * 待解决问题数（巡检）
     */
    private Long openIssues;

    /**
     * 严重问题数（巡检）
     */
    private Long criticalIssues;

    /**
     * 今日执行次数
     */
    private Long todayExecutions;

    /**
     * 今日成功次数
     */
    private Long todaySuccessExecutions;

    /**
     * 今日失败次数
     */
    private Long todayFailedExecutions;

    /**
     * 热点表窗口（天）
     */
    private Integer hotWindowDays;

    /**
     * 长期未用阈值（天）
     */
    private Integer coldWindowDays;

    /**
     * 热点表列表
     */
    @Builder.Default
    private List<DashboardTableAccessItem> hotTables = new ArrayList<>();

    /**
     * 长期未用表列表
     */
    @Builder.Default
    private List<DashboardTableAccessItem> longUnusedTables = new ArrayList<>();

    /**
     * 是否启用 Doris 审计源
     */
    private Boolean dorisAuditEnabled;

    /**
     * Doris 审计源名称
     */
    private String dorisAuditSource;

    /**
     * 表访问统计备注
     */
    private String tableAccessNote;
}
