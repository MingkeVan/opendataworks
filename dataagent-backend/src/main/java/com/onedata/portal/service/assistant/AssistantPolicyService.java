package com.onedata.portal.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.assistant.AssistantPolicyView;
import com.onedata.portal.entity.AssistantPolicyProfile;
import com.onedata.portal.mapper.AssistantPolicyProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AssistantPolicyService {

    public static final String MODE_NEED_CONFIRM = "need-confirm";
    public static final String MODE_YOLO = "yolo";

    private final AssistantPolicyProfileMapper policyProfileMapper;

    public AssistantPolicyView getPolicy(String userId) {
        AssistantPolicyProfile profile = getOrCreateProfile(userId);
        AssistantPolicyView view = new AssistantPolicyView();
        view.setMode(profile.getMode());
        return view;
    }

    public AssistantPolicyView updatePolicy(String userId, String mode) {
        if (!isValidMode(mode)) {
            throw new IllegalArgumentException("不支持的策略模式: " + mode);
        }
        AssistantPolicyProfile profile = getOrCreateProfile(userId);
        profile.setMode(mode);
        policyProfileMapper.updateById(profile);

        AssistantPolicyView view = new AssistantPolicyView();
        view.setMode(profile.getMode());
        return view;
    }

    public String resolveMode(String modeCandidate, String userId) {
        if (isValidMode(modeCandidate)) {
            return modeCandidate;
        }
        return getOrCreateProfile(userId).getMode();
    }

    private AssistantPolicyProfile getOrCreateProfile(String userId) {
        String uid = StringUtils.hasText(userId) ? userId : "anonymous";
        AssistantPolicyProfile profile = policyProfileMapper.selectOne(
            new LambdaQueryWrapper<AssistantPolicyProfile>()
                .eq(AssistantPolicyProfile::getUserId, uid)
                .last("LIMIT 1")
        );
        if (profile != null) {
            return profile;
        }

        profile = new AssistantPolicyProfile();
        profile.setUserId(uid);
        profile.setMode(MODE_NEED_CONFIRM);
        policyProfileMapper.insert(profile);
        return profile;
    }

    private boolean isValidMode(String mode) {
        return MODE_NEED_CONFIRM.equals(mode) || MODE_YOLO.equals(mode);
    }
}
