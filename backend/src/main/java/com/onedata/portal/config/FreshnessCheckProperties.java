package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据新鲜度检查配置。控制定时/工作流触发的开关与节流。
 */
@Data
@Component
@ConfigurationProperties(prefix = "freshness.check")
public class FreshnessCheckProperties {

    /** 定时与工作流触发的总开关。关闭后按需接口与巡检路径仍可用。 */
    private boolean enabled = true;

    /** 定时任务扫描间隔（毫秒）。 */
    private long fixedDelayMs = 900_000L;

    /** 同一表两次检查的最小间隔（毫秒），与 warnAfter/2 取较大者。 */
    private long minIntervalMs = 300_000L;
}
