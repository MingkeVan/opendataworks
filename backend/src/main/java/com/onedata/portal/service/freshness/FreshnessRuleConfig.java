package com.onedata.portal.service.freshness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code data_freshness} 规则的 {@code rule_config} 解析结果：规则级设置 + defaults 列表。
 */
public final class FreshnessRuleConfig {

    private static final String DEFAULT_WARN_SEVERITY = "medium";
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_CONCURRENT_PER_CLUSTER = 4;

    private final String warnSeverity;
    private final int queryTimeoutSeconds;
    private final int maxConcurrentPerCluster;
    private final boolean reportUnconfigured;
    private final List<FreshnessDefault> defaults;

    private FreshnessRuleConfig(String warnSeverity, int queryTimeoutSeconds, int maxConcurrentPerCluster,
                                boolean reportUnconfigured, List<FreshnessDefault> defaults) {
        this.warnSeverity = warnSeverity;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxConcurrentPerCluster = maxConcurrentPerCluster;
        this.reportUnconfigured = reportUnconfigured;
        this.defaults = Collections.unmodifiableList(defaults);
    }

    public String getWarnSeverity() {
        return warnSeverity;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public int getMaxConcurrentPerCluster() {
        return maxConcurrentPerCluster;
    }

    public boolean isReportUnconfigured() {
        return reportUnconfigured;
    }

    public List<FreshnessDefault> getDefaults() {
        return defaults;
    }

    @SuppressWarnings("unchecked")
    public static FreshnessRuleConfig fromMap(Map<String, Object> config) {
        if (config == null) {
            config = Collections.emptyMap();
        }
        String warnSeverity = asString(config.get("warnSeverity"), DEFAULT_WARN_SEVERITY);
        int queryTimeout = asPositiveInt(config.get("queryTimeoutSeconds"), DEFAULT_QUERY_TIMEOUT_SECONDS);
        int maxConcurrent = asPositiveInt(config.get("maxConcurrentPerCluster"), DEFAULT_MAX_CONCURRENT_PER_CLUSTER);
        boolean reportUnconfigured = Boolean.TRUE.equals(config.get("reportUnconfigured"));

        List<FreshnessDefault> defaults = new ArrayList<>();
        Object defaultsObj = config.get("defaults");
        if (defaultsObj instanceof List) {
            for (Object item : (List<Object>) defaultsObj) {
                if (item instanceof Map) {
                    defaults.add(FreshnessDefault.fromMap((Map<String, Object>) item));
                }
            }
        }
        return new FreshnessRuleConfig(warnSeverity, queryTimeout, maxConcurrent, reportUnconfigured, defaults);
    }

    private static String asString(Object obj, String fallback) {
        if (obj == null) {
            return fallback;
        }
        String s = String.valueOf(obj).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int asPositiveInt(Object obj, int fallback) {
        if (obj instanceof Number) {
            int value = ((Number) obj).intValue();
            if (value > 0) {
                return value;
            }
        }
        return fallback;
    }
}
