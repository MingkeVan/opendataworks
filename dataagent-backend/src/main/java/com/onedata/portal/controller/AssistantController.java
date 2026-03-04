package com.onedata.portal.controller;

import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.context.UserContextHolder;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.assistant.*;
import com.onedata.portal.service.assistant.AssistantPolicyService;
import com.onedata.portal.service.assistant.AssistantSessionService;
import com.onedata.portal.service.assistant.AssistantSkillService;
import com.onedata.portal.service.assistant.AssistantStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantSessionService sessionService;
    private final AssistantPolicyService policyService;
    private final AssistantSkillService skillService;
    private final AssistantStreamService streamService;

    @RequireAuth
    @PostMapping("/sessions")
    public Result<AssistantSessionView> createSession(@RequestBody(required = false) AssistantSessionCreateRequest request) {
        return Result.success(sessionService.createSession(currentUserId(), request == null ? new AssistantSessionCreateRequest() : request));
    }

    @RequireAuth
    @GetMapping("/sessions")
    public Result<List<AssistantSessionView>> listSessions() {
        return Result.success(sessionService.listSessions(currentUserId()));
    }

    @RequireAuth
    @GetMapping("/sessions/{id}")
    public Result<AssistantSessionDetailResponse> getSessionDetail(@PathVariable("id") String sessionId) {
        return Result.success(sessionService.getSessionDetail(currentUserId(), sessionId));
    }

    @RequireAuth
    @PostMapping("/sessions/{id}/messages")
    public Result<AssistantMessageSubmitResponse> postMessage(@PathVariable("id") String sessionId,
                                                              @Validated @RequestBody AssistantMessageCreateRequest request) {
        return Result.success(sessionService.submitMessage(currentUserId(), sessionId, request));
    }

    @RequireAuth
    @GetMapping(path = "/runs/{runId}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String runId) {
        // 仅校验 run 是否存在且属于当前用户
        if (sessionService.getRunByIdAndUser(runId, currentUserId()) == null) {
            throw new IllegalArgumentException("运行不存在");
        }
        return streamService.openStream(runId);
    }

    @RequireAuth
    @PostMapping("/runs/{runId}/approve")
    public Result<AssistantRunView> approve(@PathVariable String runId,
                                            @RequestBody AssistantApprovalRequest request) {
        return Result.success(sessionService.approveRun(currentUserId(), runId, request));
    }

    @RequireAuth
    @PostMapping("/runs/{runId}/cancel")
    public Result<AssistantRunView> cancel(@PathVariable String runId) {
        return Result.success(sessionService.cancelRun(currentUserId(), runId));
    }

    @RequireAuth
    @GetMapping("/policy")
    public Result<AssistantPolicyView> getPolicy() {
        return Result.success(policyService.getPolicy(currentUserId()));
    }

    @RequireAuth
    @PutMapping("/policy")
    public Result<AssistantPolicyView> updatePolicy(@RequestBody AssistantPolicyRequest request) {
        return Result.success(policyService.updatePolicy(currentUserId(), request.getMode()));
    }

    @RequireAuth
    @GetMapping("/skills")
    public Result<List<AssistantSkillRuleView>> listSkills() {
        return Result.success(skillService.listSkills(currentUserId()));
    }

    @RequireAuth
    @PutMapping("/skills/{skillKey}")
    public Result<AssistantSkillRuleView> updateSkill(@PathVariable String skillKey,
                                                      @RequestBody AssistantSkillUpdateRequest request) {
        return Result.success(skillService.updateSkill(currentUserId(), skillKey, request));
    }

    private String currentUserId() {
        return UserContextHolder.getCurrentUserId();
    }
}
