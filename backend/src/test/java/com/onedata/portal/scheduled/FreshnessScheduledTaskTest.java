package com.onedata.portal.scheduled;

import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.service.freshness.FreshnessCheckService;
import com.onedata.portal.service.freshness.FreshnessContract;
import com.onedata.portal.service.freshness.FreshnessContractResolver;
import com.onedata.portal.service.freshness.FreshnessMode;
import com.onedata.portal.service.freshness.FreshnessPeriod;
import com.onedata.portal.service.freshness.FreshnessRuleConfig;
import com.onedata.portal.service.freshness.FreshnessRuleConfigLoader;
import com.onedata.portal.service.freshness.FreshnessSource;
import com.onedata.portal.service.freshness.FreshnessThreshold;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时检查：无契约不入候选、未到期不检查、开关关闭跳过、到期判定边界。
 */
class FreshnessScheduledTaskTest {

    private final FreshnessCheckProperties properties = new FreshnessCheckProperties();
    private final FreshnessCheckService checkService = mock(FreshnessCheckService.class);
    private final FreshnessContractResolver resolver = new FreshnessContractResolver();
    private final FreshnessRuleConfigLoader ruleConfigLoader = mock(FreshnessRuleConfigLoader.class);
    private final TableFreshnessConfigMapper configMapper = mock(TableFreshnessConfigMapper.class);
    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);

    private final FreshnessScheduledTask task = new FreshnessScheduledTask(
        properties, checkService, resolver, ruleConfigLoader, configMapper, dataTableMapper);

    private TableFreshnessConfig config(long tableId) {
        TableFreshnessConfig c = new TableFreshnessConfig();
        c.setTableId(tableId);
        c.setMode("metadata");
        c.setWarnAfterCount(2);
        c.setWarnAfterPeriod("hour");
        c.setErrorAfterCount(4);
        c.setErrorAfterPeriod("hour");
        c.setEnabled(true);
        return c;
    }

    private DataTable table(long id, LocalDateTime checkedAt) {
        DataTable t = new DataTable();
        t.setId(id);
        t.setStatus("active");
        t.setClusterId(10L);
        t.setFreshnessCheckedAt(checkedAt);
        return t;
    }

    @Test
    void noConfigs_noCheck() {
        when(configMapper.selectList(any())).thenReturn(Collections.emptyList());
        task.runDueChecks();
        verify(checkService, never()).checkBatch(any(), any(), any(), any());
    }

    @Test
    void neverChecked_isDue() {
        when(configMapper.selectList(any())).thenReturn(Collections.singletonList(config(1L)));
        when(dataTableMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(table(1L, null)));
        when(ruleConfigLoader.load()).thenReturn(FreshnessRuleConfig.fromMap(Collections.emptyMap()));
        when(checkService.checkBatch(any(), any(), any(), any()))
            .thenReturn(new FreshnessCheckService.BatchOutcome(Collections.emptyList(), Collections.emptyList()));

        task.runDueChecks();
        verify(checkService).checkBatch(any(), any(), any(), any());
    }

    @Test
    void recentlyChecked_notDue() {
        when(configMapper.selectList(any())).thenReturn(Collections.singletonList(config(1L)));
        // 刚检查过（warnAfter=2h → 间隔=1h），未到期
        when(dataTableMapper.selectBatchIds(any()))
            .thenReturn(Collections.singletonList(table(1L, LocalDateTime.now().minusMinutes(5))));
        when(ruleConfigLoader.load()).thenReturn(FreshnessRuleConfig.fromMap(Collections.emptyMap()));

        task.runDueChecks();
        verify(checkService, never()).checkBatch(any(), any(), any(), any());
    }

    @Test
    void disabled_skips() {
        properties.setEnabled(false);
        task.scheduledFreshnessCheck();
        verify(configMapper, never()).selectList(any());
    }

    @Test
    void isDue_boundary() {
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.METADATA, FreshnessSource.TABLE)
            .warnAfter(new FreshnessThreshold(2, FreshnessPeriod.HOUR), FreshnessSource.TABLE)
            .build();
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0, 0);
        // 间隔 = max(minInterval=5min, warnAfter/2=1h) = 1h
        // 上次 1h 前 → 到期
        assertTrue(FreshnessScheduledTask.isDue(now.minusHours(1), contract, 300_000L, now));
        // 上次 59min 前 → 未到期
        assertFalse(FreshnessScheduledTask.isDue(now.minusMinutes(59), contract, 300_000L, now));
        // 从未检查 → 到期
        assertTrue(FreshnessScheduledTask.isDue(null, contract, 300_000L, now));
    }
}
