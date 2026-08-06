package com.onedata.portal.service.freshness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.InspectionRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 加载 {@code DATA_FRESHNESS_CHECK} 规则的配置并解析为 {@link FreshnessRuleConfig}。
 * 供定时任务与工作流触发共享 defaults/超时/并发等设置；规则缺失或解析失败时回落为默认配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreshnessRuleConfigLoader {

    public static final String RULE_CODE = "DATA_FRESHNESS_CHECK";

    private final InspectionRuleMapper inspectionRuleMapper;
    private final ObjectMapper objectMapper;

    public FreshnessRuleConfig load() {
        InspectionRule rule = inspectionRuleMapper.selectOne(
            new LambdaQueryWrapper<InspectionRule>().eq(InspectionRule::getRuleCode, RULE_CODE).last("LIMIT 1"));
        if (rule == null) {
            return FreshnessRuleConfig.fromMap(Collections.emptyMap());
        }
        return FreshnessRuleConfig.fromMap(parse(rule.getRuleConfig()));
    }

    private Map<String, Object> parse(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.trim().isEmpty() || "{}".equals(ruleConfig.trim())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(ruleConfig, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse DATA_FRESHNESS_CHECK rule config: {}", ruleConfig, e);
            return Collections.emptyMap();
        }
    }
}
