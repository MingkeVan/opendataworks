package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检查数据新鲜度 - 根据表的更新频率检查数据是否及时更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFreshnessRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "data_freshness";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        // 默认延迟容忍度(小时),允许一定的延迟
        int toleranceHours = ((Number) config.getOrDefault("toleranceHours", 2)).intValue();

        // 查询有更新频率配置的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getStatisticsCycle)
            .ne(DataTable::getStatisticsCycle, "");
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            String cycle = table.getStatisticsCycle();
            if (cycle == null || cycle.isEmpty()) {
                continue;
            }

            // 解析更新周期并计算预期更新时间阈值
            Integer expectedHours = parseUpdateCycle(cycle);
            if (expectedHours == null) {
                log.warn("Unknown statistics cycle: {} for table: {}", cycle, table.getTableName());
                continue;
            }

            // 实际阈值 = 预期更新周期 + 容忍延迟
            int thresholdHours = expectedHours + toleranceHours;
            LocalDateTime threshold = LocalDateTime.now().minusHours(thresholdHours);

            // 检查最后更新时间
            if (table.getDorisUpdateTime() == null || table.getDorisUpdateTime().isBefore(threshold)) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);

                // 根据延迟时间设置严重程度
                long delayHours = calculateDelayHours(table.getDorisUpdateTime(), expectedHours);
                issue.setSeverity(calculateFreshnessSeverity(delayHours, expectedHours));

                issue.setIssueDescription(String.format("表更新频率为 %s,但数据已 %d 小时未更新",
                    getCycleDescription(cycle), delayHours));
                issue.setCurrentValue(table.getDorisUpdateTime() != null ?
                    table.getDorisUpdateTime().toString() + " (已延迟 " + delayHours + " 小时)" : "从未更新");
                issue.setExpectedValue("更新频率: " + getCycleDescription(cycle));
                issue.setSuggestion(generateFreshnessSuggestion(cycle, delayHours));

                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 解析更新周期配置,返回预期的更新间隔(小时)
     * 支持格式:
     * - 1d, 2d, 7d: 天
     * - 1h, 6h, 12h: 小时
     * - 1w, 2w: 周
     * - 1m: 月
     */
    private Integer parseUpdateCycle(String cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return null;
        }

        cycle = cycle.toLowerCase().trim();

        try {
            // 匹配天 (1d, 2d, etc.)
            if (cycle.endsWith("d")) {
                int days = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return days * 24;
            }

            // 匹配小时 (1h, 6h, etc.)
            if (cycle.endsWith("h")) {
                return Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            }

            // 匹配周 (1w, 2w, etc.)
            if (cycle.endsWith("w")) {
                int weeks = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return weeks * 7 * 24;
            }

            // 匹配月 (1m)
            if (cycle.endsWith("m")) {
                int months = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return months * 30 * 24; // 简化为30天
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse update cycle: {}", cycle, e);
        }

        return null;
    }

    /**
     * 计算数据延迟的小时数
     */
    private long calculateDelayHours(LocalDateTime dorisUpdateTime, int expectedHours) {
        if (dorisUpdateTime == null) {
            return Long.MAX_VALUE; // 从未更新
        }

        LocalDateTime expectedTime = LocalDateTime.now().minusHours(expectedHours);
        if (dorisUpdateTime.isBefore(expectedTime)) {
            return Duration.between(dorisUpdateTime, LocalDateTime.now()).toHours();
        }

        return 0;
    }

    /**
     * 根据延迟时间计算数据新鲜度问题的严重程度
     */
    private String calculateFreshnessSeverity(long delayHours, int expectedHours) {
        if (delayHours == Long.MAX_VALUE) {
            return "critical"; // 从未更新
        }

        // 计算延迟倍数
        double delayRatio = (double) delayHours / expectedHours;

        if (delayRatio >= 3.0) {
            return "critical"; // 延迟超过3倍预期周期
        } else if (delayRatio >= 2.0) {
            return "high"; // 延迟超过2倍预期周期
        } else if (delayRatio >= 1.5) {
            return "medium"; // 延迟超过1.5倍预期周期
        } else {
            return "low"; // 轻微延迟
        }
    }

    /**
     * 获取更新周期的中文描述
     */
    private String getCycleDescription(String cycle) {
        cycle = cycle.toLowerCase().trim();

        if (cycle.endsWith("d")) {
            int days = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return days == 1 ? "每天" : "每 " + days + " 天";
        }

        if (cycle.endsWith("h")) {
            int hours = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return "每 " + hours + " 小时";
        }

        if (cycle.endsWith("w")) {
            int weeks = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return weeks == 1 ? "每周" : "每 " + weeks + " 周";
        }

        if (cycle.endsWith("m")) {
            int months = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return months == 1 ? "每月" : "每 " + months + " 月";
        }

        return cycle;
    }

    /**
     * 生成数据新鲜度问题的修复建议
     */
    private String generateFreshnessSuggestion(String cycle, long delayHours) {
        StringBuilder suggestion = new StringBuilder();

        if (delayHours == Long.MAX_VALUE) {
            suggestion.append("该表从未更新过数据,请检查:");
        } else if (delayHours >= 72) {
            suggestion.append("数据已延迟超过3天,建议立即处理:");
        } else if (delayHours >= 48) {
            suggestion.append("数据已延迟超过2天,建议尽快处理:");
        } else {
            suggestion.append("数据更新延迟,建议检查:");
        }

        suggestion.append("\n1. 检查数据同步任务是否正常运行");
        suggestion.append("\n2. 确认上游数据源是否有数据产出");
        suggestion.append("\n3. 检查任务调度配置是否正确");
        suggestion.append("\n4. 查看任务执行日志排查失败原因");

        if (delayHours >= 168) { // 超过一周
            suggestion.append("\n5. 考虑是否需要调整表的更新频率配置");
        }

        return suggestion.toString();
    }
}
