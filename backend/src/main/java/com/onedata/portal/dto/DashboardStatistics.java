package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
