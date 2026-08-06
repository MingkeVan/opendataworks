package com.onedata.portal.service.freshness;

import com.onedata.portal.entity.DataTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 规则配置 {@code rule_config.defaults[]} 中的一项：作用范围 {@code scope} + 阈值/取值字段。
 * 为表级未声明的字段兜底，等价 dbt 的 source 级默认。
 */
public final class FreshnessDefault {

    private final List<Long> clusterIds;
    private final List<String> dbNames;
    private final List<String> layers;

    private final FreshnessMode mode;
    private final String loadedAtField;
    private final String loadedAtQuery;
    private final String partitionFormat;
    private final String filterExpr;
    private final FreshnessThreshold warnAfter;
    private final FreshnessThreshold errorAfter;

    private FreshnessDefault(List<Long> clusterIds, List<String> dbNames, List<String> layers,
                            FreshnessMode mode, String loadedAtField, String loadedAtQuery,
                            String partitionFormat, String filterExpr,
                            FreshnessThreshold warnAfter, FreshnessThreshold errorAfter) {
        this.clusterIds = clusterIds;
        this.dbNames = dbNames;
        this.layers = layers;
        this.mode = mode;
        this.loadedAtField = loadedAtField;
        this.loadedAtQuery = loadedAtQuery;
        this.partitionFormat = partitionFormat;
        this.filterExpr = filterExpr;
        this.warnAfter = warnAfter;
        this.errorAfter = errorAfter;
    }

    public FreshnessMode getMode() {
        return mode;
    }

    public String getLoadedAtField() {
        return loadedAtField;
    }

    public String getLoadedAtQuery() {
        return loadedAtQuery;
    }

    public String getPartitionFormat() {
        return partitionFormat;
    }

    public String getFilterExpr() {
        return filterExpr;
    }

    public FreshnessThreshold getWarnAfter() {
        return warnAfter;
    }

    public FreshnessThreshold getErrorAfter() {
        return errorAfter;
    }

    /**
     * scope 各维度均为「命中或未限定」时判定命中。未限定的维度视为通配。
     */
    public boolean matches(DataTable table) {
        if (!clusterIds.isEmpty() && !clusterIds.contains(table.getClusterId())) {
            return false;
        }
        if (!dbNames.isEmpty() && !containsIgnoreCase(dbNames, table.getDbName())) {
            return false;
        }
        if (!layers.isEmpty() && !containsIgnoreCase(layers, table.getLayer())) {
            return false;
        }
        return true;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String target) {
        if (target == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 defaults 数组的一个元素解析。非法/缺失字段安全忽略。
     */
    @SuppressWarnings("unchecked")
    public static FreshnessDefault fromMap(Map<String, Object> map) {
        Object scopeObj = map.get("scope");
        Map<String, Object> scope = scopeObj instanceof Map ? (Map<String, Object>) scopeObj : null;

        return new FreshnessDefault(
            toLongList(scope, "clusterIds", "clusterId"),
            toStringList(scope, "dbNames", "dbName"),
            toStringList(scope, "layers", "layer"),
            FreshnessMode.parse(asString(map.get("mode"))).orElse(null),
            trimToNull(asString(map.get("loadedAtField"))),
            trimToNull(asString(map.get("loadedAtQuery"))),
            trimToNull(asString(map.get("partitionFormat"))),
            trimToNull(asString(map.get("filterExpr"))),
            parseThreshold(map.get("warnAfter")),
            parseThreshold(map.get("errorAfter"))
        );
    }

    @SuppressWarnings("unchecked")
    static FreshnessThreshold parseThreshold(Object obj) {
        if (!(obj instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) obj;
        Object countObj = map.get("count");
        if (!(countObj instanceof Number)) {
            return null;
        }
        int count = ((Number) countObj).intValue();
        if (count <= 0) {
            return null;
        }
        return FreshnessPeriod.parse(asString(map.get("period")))
            .map(period -> new FreshnessThreshold(count, period))
            .orElse(null);
    }

    private static List<Long> toLongList(Map<String, Object> scope, String... keys) {
        List<Long> result = new ArrayList<>();
        if (scope == null) {
            return result;
        }
        for (String key : keys) {
            Object value = scope.get(key);
            if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    if (item instanceof Number) {
                        result.add(((Number) item).longValue());
                    }
                }
            } else if (value instanceof Number) {
                result.add(((Number) value).longValue());
            }
        }
        return result;
    }

    private static List<String> toStringList(Map<String, Object> scope, String... keys) {
        List<String> result = new ArrayList<>();
        if (scope == null) {
            return result;
        }
        for (String key : keys) {
            Object value = scope.get(key);
            if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) {
                    String s = trimToNull(asString(item));
                    if (s != null) {
                        result.add(s);
                    }
                }
            } else {
                String s = trimToNull(asString(value));
                if (s != null) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    private static String asString(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
