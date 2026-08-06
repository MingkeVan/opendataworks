package com.onedata.portal.service.freshness;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 新鲜度契约解析。只有两层来源，逐字段合并，语义对齐 dbt {@code merge_freshness}：
 * 上层只覆盖它自己声明过的字段。
 *
 * <ol>
 *   <li>表级 {@link TableFreshnessConfig}（{@code enabled = 0} 短路，等价 {@code freshness: null}）</li>
 *   <li>规则配置 {@code defaults[]} 中首个命中 scope 的项</li>
 * </ol>
 *
 * <p>合并后仍无任何阈值 → 返回空，该表不检查、不产出结论。
 * 不实现任何从 {@code statistics_cycle} / {@code schedule_cron} / 列名模式推导的逻辑。
 *
 * <p>纯组件：不注入 Mapper，表级配置与 defaults 由调用方加载/解析后传入，便于批量与单测。
 */
@Component
public class FreshnessContractResolver {

    /**
     * @param table      数据表
     * @param tableConfig 表级契约，可为 null
     * @param defaults   规则默认列表，可为空
     * @return 可检查的契约；表级显式关闭、或无任何阈值时返回空
     */
    public Optional<FreshnessContract> resolve(DataTable table,
                                               TableFreshnessConfig tableConfig,
                                               List<FreshnessDefault> defaults) {
        // 表级显式关闭：短路，不检查
        if (tableConfig != null && Boolean.FALSE.equals(tableConfig.getEnabled())) {
            return Optional.empty();
        }

        FreshnessContract.Builder builder = FreshnessContract.builder();

        // 第一层：表级配置（首个非空值胜出，先加）
        if (tableConfig != null) {
            builder.mode(FreshnessMode.parse(tableConfig.getMode()).orElse(null), FreshnessSource.TABLE)
                .loadedAtField(trimToNull(tableConfig.getLoadedAtField()), FreshnessSource.TABLE)
                .loadedAtQuery(trimToNull(tableConfig.getLoadedAtQuery()), FreshnessSource.TABLE)
                .partitionFormat(trimToNull(tableConfig.getPartitionFormat()), FreshnessSource.TABLE)
                .filterExpr(trimToNull(tableConfig.getFilterExpr()), FreshnessSource.TABLE)
                .warnAfter(threshold(tableConfig.getWarnAfterCount(), tableConfig.getWarnAfterPeriod()), FreshnessSource.TABLE)
                .errorAfter(threshold(tableConfig.getErrorAfterCount(), tableConfig.getErrorAfterPeriod()), FreshnessSource.TABLE);
        }

        // 第二层：首个命中 scope 的规则默认，为表级未声明字段兜底
        FreshnessDefault matched = firstMatch(table, defaults);
        if (matched != null) {
            builder.mode(matched.getMode(), FreshnessSource.RULE_DEFAULT)
                .loadedAtField(matched.getLoadedAtField(), FreshnessSource.RULE_DEFAULT)
                .loadedAtQuery(matched.getLoadedAtQuery(), FreshnessSource.RULE_DEFAULT)
                .partitionFormat(matched.getPartitionFormat(), FreshnessSource.RULE_DEFAULT)
                .filterExpr(matched.getFilterExpr(), FreshnessSource.RULE_DEFAULT)
                .warnAfter(matched.getWarnAfter(), FreshnessSource.RULE_DEFAULT)
                .errorAfter(matched.getErrorAfter(), FreshnessSource.RULE_DEFAULT);
        }

        FreshnessContract contract = builder.build();
        return contract.isCheckable() ? Optional.of(contract) : Optional.empty();
    }

    private FreshnessDefault firstMatch(DataTable table, List<FreshnessDefault> defaults) {
        if (defaults == null) {
            return null;
        }
        for (FreshnessDefault candidate : defaults) {
            if (candidate.matches(table)) {
                return candidate;
            }
        }
        return null;
    }

    private FreshnessThreshold threshold(Integer count, String period) {
        if (count == null || count <= 0) {
            return null;
        }
        return FreshnessPeriod.parse(period)
            .map(p -> new FreshnessThreshold(count, p))
            .orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
