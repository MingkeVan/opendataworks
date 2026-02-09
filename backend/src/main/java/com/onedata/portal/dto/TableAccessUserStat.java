package com.onedata.portal.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表访问用户统计
 */
@Data
public class TableAccessUserStat {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 访问次数
     */
    private Long accessCount;

    /**
     * 最近访问时间
     */
    private LocalDateTime lastAccessTime;
}
