package com.onedata.portal.service.freshness;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约解析：两层来源、逐字段合并、显式关闭短路、无阈值不检查。
 */
class FreshnessContractResolverTest {

    private final FreshnessContractResolver resolver = new FreshnessContractResolver();

    private DataTable table(Long clusterId, String dbName, String layer) {
        DataTable t = new DataTable();
        t.setId(1L);
        t.setClusterId(clusterId);
        t.setDbName(dbName);
        t.setLayer(layer);
        return t;
    }

    private TableFreshnessConfig tableConfig(String mode, String loadedAtField,
                                             Integer warnCount, String warnPeriod,
                                             Integer errCount, String errPeriod) {
        TableFreshnessConfig c = new TableFreshnessConfig();
        c.setMode(mode);
        c.setLoadedAtField(loadedAtField);
        c.setWarnAfterCount(warnCount);
        c.setWarnAfterPeriod(warnPeriod);
        c.setErrorAfterCount(errCount);
        c.setErrorAfterPeriod(errPeriod);
        c.setEnabled(true);
        return c;
    }

    private List<FreshnessDefault> defaults(Map<String, Object>... items) {
        return Arrays.stream(items).map(FreshnessDefault::fromMap).collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> defaultItem(Map<String, Object> scope, Integer warn, Integer error) {
        Map<String, Object> m = new HashMap<>();
        if (scope != null) {
            m.put("scope", scope);
        }
        if (warn != null) {
            m.put("warnAfter", threshold(warn, "hour"));
        }
        if (error != null) {
            m.put("errorAfter", threshold(error, "hour"));
        }
        return m;
    }

    private Map<String, Object> threshold(int count, String period) {
        Map<String, Object> m = new HashMap<>();
        m.put("count", count);
        m.put("period", period);
        return m;
    }

    @Test
    void noConfigNoDefaults_returnsEmpty() {
        Optional<FreshnessContract> result =
            resolver.resolve(table(1L, "dwd", "DWD"), null, Collections.emptyList());
        assertFalse(result.isPresent());
    }

    @Test
    void disabledTableConfig_shortCircuits() {
        TableFreshnessConfig c = tableConfig("column", "etl_time", 2, "hour", 4, "hour");
        c.setEnabled(false);
        // 即便有能命中的默认，也必须短路为不检查
        List<FreshnessDefault> defs = defaults(defaultItem(null, 2, 4));
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertFalse(result.isPresent());
    }

    @Test
    void tableConfigOnly_fullyResolved() {
        TableFreshnessConfig c = tableConfig("column", "etl_time", 2, "hour", 4, "hour");
        Optional<FreshnessContract> result =
            resolver.resolve(table(1L, "dwd", "DWD"), c, Collections.emptyList());
        assertTrue(result.isPresent());
        FreshnessContract contract = result.get();
        assertEquals(FreshnessMode.COLUMN, contract.getMode());
        assertEquals("etl_time", contract.getLoadedAtField());
        assertEquals(2, contract.getWarnAfter().getCount());
        assertEquals(4, contract.getErrorAfter().getCount());
        assertEquals(FreshnessSource.TABLE, contract.sourceOf(FreshnessContract.F_WARN_AFTER));
    }

    @Test
    void perFieldMerge_tableModeDefaultThresholds() {
        // 表级只声明取数方式，阈值来自规则默认
        TableFreshnessConfig c = tableConfig("column", "etl_time", null, null, null, null);
        List<FreshnessDefault> defs = defaults(defaultItem(null, 2, 4));
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertTrue(result.isPresent());
        FreshnessContract contract = result.get();
        assertEquals(FreshnessMode.COLUMN, contract.getMode());
        assertEquals("etl_time", contract.getLoadedAtField());
        assertEquals(2, contract.getWarnAfter().getCount());
        assertEquals(FreshnessSource.TABLE, contract.sourceOf(FreshnessContract.F_LOADED_AT_FIELD));
        assertEquals(FreshnessSource.RULE_DEFAULT, contract.sourceOf(FreshnessContract.F_WARN_AFTER));
    }

    @Test
    void tableConfigWinsOverDefault_perField() {
        // 表级声明 warnAfter=2h，规则默认 warnAfter=9h：表级胜出
        TableFreshnessConfig c = tableConfig("column", "etl_time", 2, "hour", null, null);
        List<FreshnessDefault> defs = defaults(defaultItem(null, 9, 9));
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertTrue(result.isPresent());
        FreshnessContract contract = result.get();
        assertEquals(2, contract.getWarnAfter().getCount());
        assertEquals(FreshnessSource.TABLE, contract.sourceOf(FreshnessContract.F_WARN_AFTER));
        // errorAfter 表级未声明，取默认
        assertEquals(9, contract.getErrorAfter().getCount());
        assertEquals(FreshnessSource.RULE_DEFAULT, contract.sourceOf(FreshnessContract.F_ERROR_AFTER));
    }

    @Test
    void defaultScopeMiss_doesNotApply() {
        // 默认只作用于 layer=ADS，表是 DWD，不套用 → 无阈值 → 不检查
        TableFreshnessConfig c = tableConfig("column", "etl_time", null, null, null, null);
        Map<String, Object> scope = new HashMap<>();
        scope.put("layers", Collections.singletonList("ADS"));
        List<FreshnessDefault> defs = defaults(defaultItem(scope, 2, 4));
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertFalse(result.isPresent());
    }

    @Test
    void defaultScopeHit_byLayer() {
        TableFreshnessConfig c = tableConfig("metadata", null, null, null, null, null);
        Map<String, Object> scope = new HashMap<>();
        scope.put("layers", Collections.singletonList("DWD"));
        List<FreshnessDefault> defs = defaults(defaultItem(scope, 2, 4));
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getWarnAfter().getCount());
    }

    @Test
    void firstMatchingDefaultWins() {
        Map<String, Object> scopeDwd = new HashMap<>();
        scopeDwd.put("layers", Collections.singletonList("DWD"));
        List<FreshnessDefault> defs = defaults(
            defaultItem(scopeDwd, 2, 4),   // 命中
            defaultItem(null, 9, 9));      // 通配，靠后
        TableFreshnessConfig c = tableConfig("metadata", null, null, null, null, null);
        Optional<FreshnessContract> result = resolver.resolve(table(1L, "dwd", "DWD"), c, defs);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getWarnAfter().getCount());
    }

    @Test
    void onlyWarnThreshold_isCheckable() {
        TableFreshnessConfig c = tableConfig("metadata", null, 2, "hour", null, null);
        Optional<FreshnessContract> result =
            resolver.resolve(table(1L, "dwd", "DWD"), c, Collections.emptyList());
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getWarnAfter().getCount());
    }
}
