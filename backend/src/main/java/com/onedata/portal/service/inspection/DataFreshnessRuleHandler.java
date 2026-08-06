package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.service.freshness.FreshnessCheckResult;
import com.onedata.portal.service.freshness.FreshnessCheckService;
import com.onedata.portal.service.freshness.FreshnessRuleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据新鲜度检查规则。
 *
 * <p>按表级新鲜度契约（{@code table_freshness_config} + 规则 {@code defaults}）检查数据是否在
 * 约定时限内更新，语义对齐 dbt source freshness。委托 {@link FreshnessCheckService} 执行取数与
 * 判定，本 handler 只负责取表范围、把非 {@code pass} 结果映射为巡检问题。未配置契约的表不参与
 * 检查、不产出结论；仅当 {@code reportUnconfigured} 打开时产出一条治理型问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataFreshnessRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;
    private final FreshnessCheckService freshnessCheckService;

    @Override
    public String ruleType() {
        return "data_freshness";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> rawConfig = support.parseRuleConfig(rule.getRuleConfig());
        FreshnessRuleConfig ruleConfig = FreshnessRuleConfig.fromMap(rawConfig);

        // 取 scope 内的活跃表；不再要求 statistics_cycle 非空，契约可来自表级配置或规则默认
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active");
        support.applyTableScope(tableWrapper, rawConfig);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);
        if (tables.isEmpty()) {
            return issues;
        }

        Map<Long, DataTable> tableById = new HashMap<>();
        for (DataTable table : tables) {
            tableById.put(table.getId(), table);
        }

        FreshnessCheckService.BatchOutcome outcome =
            freshnessCheckService.checkBatch(tables, ruleConfig, "inspection", "system");

        // 非 pass 结果 → 巡检问题
        for (FreshnessCheckResult result : outcome.getResults()) {
            if (result.isPass()) {
                continue;
            }
            DataTable table = tableById.get(result.getTableId());
            if (table == null) {
                continue;
            }
            InspectionIssue issue = support.createIssue(recordId, rule, table);
            issue.setSeverity(mapSeverity(result.getStatus(), ruleConfig.getWarnSeverity()));
            issue.setIssueDescription(describe(result));
            issue.setCurrentValue(currentValue(result));
            issue.setExpectedValue(expectedValue(result));
            issue.setSuggestion(suggestion(result));
            support.insertIssue(issue);
            issues.add(issue);
        }

        // 治理型上报：未配置契约的表
        if (ruleConfig.isReportUnconfigured()) {
            for (DataTable table : outcome.getUnconfiguredTables()) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setSeverity("low");
                issue.setIssueDescription("该表未纳入数据新鲜度管理");
                issue.setCurrentValue("未配置新鲜度契约");
                issue.setExpectedValue("配置时间字段与 warn/error 阈值");
                issue.setSuggestion("请在数据表「数据新鲜度」页签配置契约，指定加载时间字段(或分区)与时效阈值,"
                    + "以便对该表数据是否按时更新进行监控");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    private String mapSeverity(String status, String warnSeverity) {
        switch (status) {
            case FreshnessCheckResult.STATUS_ERROR:
                return "critical";
            case FreshnessCheckResult.STATUS_RUNTIME_ERROR:
                return "high";
            case FreshnessCheckResult.STATUS_WARN:
            default:
                return warnSeverity;
        }
    }

    private String describe(FreshnessCheckResult result) {
        if (FreshnessCheckResult.STATUS_RUNTIME_ERROR.equals(result.getStatus())) {
            return "数据新鲜度检查执行失败: " + safe(result.getErrorMessage());
        }
        if (FreshnessCheckResult.REASON_NEVER_LOADED.equals(result.getReason())) {
            return "该表从未产出过数据";
        }
        String age = humanizeSeconds(result.getAgeSeconds());
        if (FreshnessCheckResult.STATUS_ERROR.equals(result.getStatus())) {
            return "数据已超过 error 时限未更新" + (age != null ? "，当前已 " + age : "");
        }
        return "数据已超过 warn 时限未更新" + (age != null ? "，当前已 " + age : "");
    }

    private String currentValue(FreshnessCheckResult result) {
        if (FreshnessCheckResult.STATUS_RUNTIME_ERROR.equals(result.getStatus())) {
            return "检查失败";
        }
        if (result.getMaxLoadedAt() == null) {
            return "从未更新";
        }
        String age = humanizeSeconds(result.getAgeSeconds());
        return result.getMaxLoadedAt() + (age != null ? "（已延迟 " + age + "）" : "");
    }

    private String expectedValue(FreshnessCheckResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.getWarnAfterSeconds() != null) {
            sb.append("warn: ").append(humanizeSeconds(result.getWarnAfterSeconds()));
        }
        if (result.getErrorAfterSeconds() != null) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("error: ").append(humanizeSeconds(result.getErrorAfterSeconds()));
        }
        return sb.length() > 0 ? sb.toString() : "按约定时限更新";
    }

    private String suggestion(FreshnessCheckResult result) {
        if (FreshnessCheckResult.STATUS_RUNTIME_ERROR.equals(result.getStatus())) {
            return "新鲜度取数失败，请检查：\n"
                + "1. 契约配置的时间字段/分区格式/自定义查询是否正确\n"
                + "2. 检查账号是否有该表的读取权限\n"
                + "3. Doris 集群连通性与该表是否存在";
        }
        StringBuilder sb = new StringBuilder();
        if (result.getMaxLoadedAt() == null) {
            sb.append("该表从未产出过数据，请检查：");
        } else {
            sb.append("数据更新延迟，请检查：");
        }
        sb.append("\n1. 生产该表的数据同步/加工任务是否正常运行");
        sb.append("\n2. 上游数据源是否有数据产出");
        sb.append("\n3. 任务调度配置与执行日志");
        return sb.toString();
    }

    private String humanizeSeconds(Long seconds) {
        if (seconds == null) {
            return null;
        }
        Duration duration = Duration.ofSeconds(Math.max(0, seconds));
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        if (days > 0) {
            return hours > 0 ? days + " 天 " + hours + " 小时" : days + " 天";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + " 小时 " + minutes + " 分钟" : hours + " 小时";
        }
        return minutes + " 分钟";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
