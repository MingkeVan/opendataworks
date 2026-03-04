package com.onedata.portal.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.assistant.AssistantSkillUpdateRequest;
import com.onedata.portal.dto.assistant.AssistantSkillRuleView;
import com.onedata.portal.entity.AssistantSkillRule;
import com.onedata.portal.mapper.AssistantSkillRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantSkillService {

    public static final String SQL_SAFETY_SKILL = "sql_safety_skill";
    public static final String SQL_QUALITY_SKILL = "sql_quality_skill";
    public static final String BI_RECOMMEND_SKILL = "bi_recommend_skill";
    public static final String EXECUTION_POLICY_SKILL = "execution_policy_skill";

    private static final List<String> DEFAULT_KEYS = Arrays.asList(
        SQL_SAFETY_SKILL,
        SQL_QUALITY_SKILL,
        BI_RECOMMEND_SKILL,
        EXECUTION_POLICY_SKILL
    );

    private final AssistantSkillRuleMapper skillRuleMapper;

    public List<AssistantSkillRuleView> listSkills(String userId) {
        String uid = normalizeUserId(userId);
        ensureDefaults(uid);

        List<AssistantSkillRule> rules = skillRuleMapper.selectList(
            new LambdaQueryWrapper<AssistantSkillRule>()
                .eq(AssistantSkillRule::getUserId, uid)
                .orderByAsc(AssistantSkillRule::getSkillKey)
        );

        List<AssistantSkillRuleView> views = new ArrayList<AssistantSkillRuleView>();
        for (AssistantSkillRule rule : rules) {
            views.add(toView(rule));
        }
        return views;
    }

    public Map<String, AssistantSkillRuleView> listSkillMap(String userId) {
        List<AssistantSkillRuleView> views = listSkills(userId);
        Map<String, AssistantSkillRuleView> map = new LinkedHashMap<String, AssistantSkillRuleView>();
        for (AssistantSkillRuleView view : views) {
            map.put(view.getSkillKey(), view);
        }
        return map;
    }

    public AssistantSkillRuleView updateSkill(String userId, String skillKey, AssistantSkillUpdateRequest request) {
        String uid = normalizeUserId(userId);
        ensureDefaults(uid);

        AssistantSkillRule rule = skillRuleMapper.selectOne(
            new LambdaQueryWrapper<AssistantSkillRule>()
                .eq(AssistantSkillRule::getUserId, uid)
                .eq(AssistantSkillRule::getSkillKey, skillKey)
                .last("LIMIT 1")
        );
        if (rule == null) {
            rule = createDefault(uid, skillKey);
            skillRuleMapper.insert(rule);
        }

        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled() ? 1 : 0);
        }
        if (request.getThresholdJson() != null) {
            rule.setThresholdJson(request.getThresholdJson());
        }
        if (request.getVersion() != null && request.getVersion() > 0) {
            rule.setVersion(request.getVersion());
        }

        skillRuleMapper.updateById(rule);
        return toView(rule);
    }

    public boolean isEnabled(String userId, String skillKey) {
        String uid = normalizeUserId(userId);
        ensureDefaults(uid);
        AssistantSkillRule rule = skillRuleMapper.selectOne(
            new LambdaQueryWrapper<AssistantSkillRule>()
                .eq(AssistantSkillRule::getUserId, uid)
                .eq(AssistantSkillRule::getSkillKey, skillKey)
                .last("LIMIT 1")
        );
        return rule != null && Integer.valueOf(1).equals(rule.getEnabled());
    }

    public String getThresholdJson(String userId, String skillKey) {
        String uid = normalizeUserId(userId);
        ensureDefaults(uid);
        AssistantSkillRule rule = skillRuleMapper.selectOne(
            new LambdaQueryWrapper<AssistantSkillRule>()
                .eq(AssistantSkillRule::getUserId, uid)
                .eq(AssistantSkillRule::getSkillKey, skillKey)
                .last("LIMIT 1")
        );
        return rule == null ? null : rule.getThresholdJson();
    }

    private void ensureDefaults(String userId) {
        for (String key : DEFAULT_KEYS) {
            AssistantSkillRule existing = skillRuleMapper.selectOne(
                new LambdaQueryWrapper<AssistantSkillRule>()
                    .eq(AssistantSkillRule::getUserId, userId)
                    .eq(AssistantSkillRule::getSkillKey, key)
                    .last("LIMIT 1")
            );
            if (existing != null) {
                continue;
            }
            skillRuleMapper.insert(createDefault(userId, key));
        }
    }

    private AssistantSkillRule createDefault(String userId, String key) {
        AssistantSkillRule rule = new AssistantSkillRule();
        rule.setUserId(userId);
        rule.setSkillKey(key);
        rule.setEnabled(1);
        rule.setVersion(1);
        rule.setThresholdJson(defaultThreshold(key));
        return rule;
    }

    private String defaultThreshold(String key) {
        if (SQL_SAFETY_SKILL.equals(key)) {
            return "{\"highRiskTypes\":[\"DROP\",\"TRUNCATE\",\"DELETE\",\"UPDATE\",\"ALTER\"],\"maxStatements\":50}";
        }
        if (SQL_QUALITY_SKILL.equals(key)) {
            return "{\"maxRetries\":3,\"emptyResultAllowed\":true}";
        }
        if (BI_RECOMMEND_SKILL.equals(key)) {
            return "{\"maxSeries\":6,\"defaultChart\":\"bar\"}";
        }
        if (EXECUTION_POLICY_SKILL.equals(key)) {
            return "{\"strictBlock\":true,\"autoApproveInYolo\":true}";
        }
        return "{}";
    }

    private AssistantSkillRuleView toView(AssistantSkillRule rule) {
        AssistantSkillRuleView view = new AssistantSkillRuleView();
        view.setSkillKey(rule.getSkillKey());
        view.setEnabled(Integer.valueOf(1).equals(rule.getEnabled()));
        view.setThresholdJson(rule.getThresholdJson());
        view.setVersion(rule.getVersion());
        return view;
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : "anonymous";
    }
}
