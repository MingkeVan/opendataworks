package com.onedata.portal.service.inspection;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.service.freshness.FreshnessCheckResult;
import com.onedata.portal.service.freshness.FreshnessCheckService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 巡检规则映射：warn/error/runtime_error → issue，pass 不产生 issue，
 * 未配置表默认不上报、reportUnconfigured 打开时产出治理型 issue。
 */
class DataFreshnessRuleHandlerTest {

    private final InspectionSupport support = mock(InspectionSupport.class);
    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);
    private final FreshnessCheckService checkService = mock(FreshnessCheckService.class);

    private final DataFreshnessRuleHandler handler =
        new DataFreshnessRuleHandler(support, dataTableMapper, checkService);

    private final List<InspectionIssue> inserted = new ArrayList<>();

    private InspectionRule rule(String config) {
        InspectionRule rule = new InspectionRule();
        rule.setRuleType("data_freshness");
        rule.setSeverity("high");
        rule.setRuleConfig(config);
        return rule;
    }

    private DataTable table(long id) {
        DataTable t = new DataTable();
        t.setId(id);
        t.setClusterId(10L);
        t.setDbName("dwd");
        t.setTableName("t" + id);
        return t;
    }

    private FreshnessCheckResult result(long tableId, String status) {
        FreshnessCheckResult r = new FreshnessCheckResult();
        r.setTableId(tableId);
        r.setStatus(status);
        r.setWarnAfterSeconds(7200L);
        r.setErrorAfterSeconds(14400L);
        return r;
    }

    private void wireSupport() {
        // createIssue 返回真实对象，insertIssue 收集
        when(support.parseRuleConfig(any())).thenAnswer(inv -> {
            String cfg = inv.getArgument(0);
            if (cfg == null || cfg.trim().isEmpty()) {
                return new java.util.HashMap<String, Object>();
            }
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(cfg, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        });
        when(support.createIssue(any(), any(), any())).thenAnswer(inv -> {
            InspectionIssue issue = new InspectionIssue();
            DataTable table = inv.getArgument(2);
            issue.setResourceId(table.getId());
            return issue;
        });
        doAnswer(inv -> {
            inserted.add(inv.getArgument(0));
            return null;
        }).when(support).insertIssue(any());
    }

    @Test
    void mapsNonPassResults_toIssues() {
        wireSupport();
        List<DataTable> tables = Arrays.asList(table(1), table(2), table(3), table(4));
        when(dataTableMapper.selectList(any())).thenReturn(tables);

        List<FreshnessCheckResult> results = Arrays.asList(
            result(1, FreshnessCheckResult.STATUS_PASS),
            result(2, FreshnessCheckResult.STATUS_WARN),
            result(3, FreshnessCheckResult.STATUS_ERROR),
            result(4, FreshnessCheckResult.STATUS_RUNTIME_ERROR));
        when(checkService.checkBatch(any(), any(), any(), any()))
            .thenReturn(new FreshnessCheckService.BatchOutcome(results, Collections.emptyList()));

        List<InspectionIssue> issues = handler.check(100L, rule("{\"warnSeverity\":\"medium\"}"));

        // pass 不产生，其余三条产生
        assertEquals(3, issues.size());
        assertEquals("medium", severityOf(issues, 2L));
        assertEquals("critical", severityOf(issues, 3L));
        assertEquals("high", severityOf(issues, 4L));
    }

    @Test
    void unconfigured_notReportedByDefault() {
        wireSupport();
        List<DataTable> tables = Collections.singletonList(table(1));
        when(dataTableMapper.selectList(any())).thenReturn(tables);
        when(checkService.checkBatch(any(), any(), any(), any()))
            .thenReturn(new FreshnessCheckService.BatchOutcome(
                Collections.emptyList(), Collections.singletonList(table(1))));

        List<InspectionIssue> issues = handler.check(100L, rule("{}"));
        assertTrue(issues.isEmpty());
    }

    @Test
    void unconfigured_reportedWhenEnabled() {
        wireSupport();
        List<DataTable> tables = Collections.singletonList(table(1));
        when(dataTableMapper.selectList(any())).thenReturn(tables);
        when(checkService.checkBatch(any(), any(), any(), any()))
            .thenReturn(new FreshnessCheckService.BatchOutcome(
                Collections.emptyList(), Collections.singletonList(table(1))));

        List<InspectionIssue> issues = handler.check(100L, rule("{\"reportUnconfigured\":true}"));
        assertEquals(1, issues.size());
        assertEquals("low", issues.get(0).getSeverity());
    }

    private String severityOf(List<InspectionIssue> issues, Long resourceId) {
        return issues.stream()
            .filter(i -> resourceId.equals(i.getResourceId()))
            .findFirst()
            .map(InspectionIssue::getSeverity)
            .orElse(null);
    }
}
