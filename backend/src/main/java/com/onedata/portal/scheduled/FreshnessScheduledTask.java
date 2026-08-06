package com.onedata.portal.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.service.freshness.FreshnessCheckService;
import com.onedata.portal.service.freshness.FreshnessContract;
import com.onedata.portal.service.freshness.FreshnessContractResolver;
import com.onedata.portal.service.freshness.FreshnessRuleConfig;
import com.onedata.portal.service.freshness.FreshnessRuleConfigLoader;
import com.onedata.portal.service.freshness.FreshnessThreshold;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 数据新鲜度定时检查。候选集为「存在启用中表级契约」的活跃表，每轮只挑到期的表，
 * 未配置契约的表不入候选，故无表配置时空跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreshnessScheduledTask {

    private final FreshnessCheckProperties properties;
    private final FreshnessCheckService freshnessCheckService;
    private final FreshnessContractResolver contractResolver;
    private final FreshnessRuleConfigLoader ruleConfigLoader;
    private final TableFreshnessConfigMapper freshnessConfigMapper;
    private final DataTableMapper dataTableMapper;

    @Scheduled(fixedDelayString = "${freshness.check.fixed-delay-ms:900000}")
    public void scheduledFreshnessCheck() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            runDueChecks();
        } catch (Exception e) {
            log.error("Scheduled freshness check failed", e);
        }
    }

    /**
     * 挑选到期的、有启用契约的表并批量检查。异常向上抛给调度包装层记录。
     */
    void runDueChecks() {
        List<TableFreshnessConfig> configs = freshnessConfigMapper.selectList(
            new LambdaQueryWrapper<TableFreshnessConfig>().eq(TableFreshnessConfig::getEnabled, true));
        if (configs.isEmpty()) {
            return;
        }
        List<Long> tableIds = configs.stream().map(TableFreshnessConfig::getTableId).collect(Collectors.toList());
        List<DataTable> candidates = dataTableMapper.selectBatchIds(tableIds).stream()
            .filter(t -> "active".equals(t.getStatus()))
            .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return;
        }

        FreshnessRuleConfig ruleConfig = ruleConfigLoader.load();
        java.util.Map<Long, TableFreshnessConfig> configByTable = configs.stream()
            .collect(Collectors.toMap(TableFreshnessConfig::getTableId, c -> c, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<DataTable> due = new ArrayList<>();
        for (DataTable table : candidates) {
            Optional<FreshnessContract> contract =
                contractResolver.resolve(table, configByTable.get(table.getId()), ruleConfig.getDefaults());
            if (contract.isPresent()
                && isDue(table.getFreshnessCheckedAt(), contract.get(), properties.getMinIntervalMs(), now)) {
                due.add(table);
            }
        }
        if (due.isEmpty()) {
            return;
        }

        FreshnessCheckService.BatchOutcome outcome =
            freshnessCheckService.checkBatch(due, ruleConfig, "schedule", "system");
        log.info("Scheduled freshness check done: candidates={}, due={}, checked={}",
            candidates.size(), due.size(), outcome.getResults().size());
    }

    /**
     * 到期判定：{@code nextCheckAt = lastChecked + max(minInterval, warnAfter/2)}。
     * 从未检查过的表立即到期。
     */
    static boolean isDue(LocalDateTime lastChecked, FreshnessContract contract, long minIntervalMs, LocalDateTime now) {
        if (lastChecked == null) {
            return true;
        }
        FreshnessThreshold gate = contract.getWarnAfter() != null ? contract.getWarnAfter() : contract.getErrorAfter();
        long halfThresholdMs = gate != null ? gate.toDuration().toMillis() / 2 : 0L;
        long intervalMs = Math.max(minIntervalMs, halfThresholdMs);
        LocalDateTime nextCheckAt = lastChecked.plus(Duration.ofMillis(intervalMs));
        return !now.isBefore(nextCheckAt);
    }
}
