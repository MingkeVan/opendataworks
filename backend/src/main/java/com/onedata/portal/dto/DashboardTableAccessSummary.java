package com.onedata.portal.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard 表访问统计汇总
 */
@Data
public class DashboardTableAccessSummary {

    private Integer hotWindowDays;

    private Integer coldWindowDays;

    private List<DashboardTableAccessItem> hotTables = new ArrayList<>();

    private List<DashboardTableAccessItem> longUnusedTables = new ArrayList<>();

    private Boolean dorisAuditEnabled;

    private String dorisAuditSource;

    private String note;
}
