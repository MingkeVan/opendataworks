package com.onedata.portal.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.context.UserContextHolder;
import com.onedata.portal.dto.assistant.*;
import com.onedata.portal.entity.AssistantArtifact;
import com.onedata.portal.entity.AssistantMessage;
import com.onedata.portal.entity.AssistantRun;
import com.onedata.portal.entity.AssistantSession;
import com.onedata.portal.mapper.AssistantArtifactMapper;
import com.onedata.portal.mapper.AssistantMessageMapper;
import com.onedata.portal.mapper.AssistantRunMapper;
import com.onedata.portal.mapper.AssistantSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssistantSessionService {

    public static final String RUN_STATUS_QUEUED = "queued";

    private final AssistantSessionMapper sessionMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantRunMapper runMapper;
    private final AssistantArtifactMapper artifactMapper;
    private final AssistantPolicyService policyService;
    private final AssistantGraphOrchestrator graphOrchestrator;
    private final ObjectMapper objectMapper;

    public AssistantSessionView createSession(String userId, AssistantSessionCreateRequest request) {
        String uid = normalizeUserId(userId);
        AssistantContextDTO context = request == null ? null : request.getContext();

        AssistantSession session = new AssistantSession();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(uid);
        session.setTitle(StringUtils.hasText(request != null ? request.getTitle() : null) ? request.getTitle() : "新会话");
        session.setSourceId(context == null ? null : context.getSourceId());
        session.setDatabaseName(context == null ? null : context.getDatabase());
        session.setLimitProfile(context == null || !StringUtils.hasText(context.getLimitProfile())
            ? "text_answer" : context.getLimitProfile());
        session.setManualLimit(context == null ? null : context.getManualLimit());
        session.setMode(policyService.resolveMode(context == null ? null : context.getMode(), uid));
        session.setStatus("active");
        session.setLastMessageAt(LocalDateTime.now());

        sessionMapper.insert(session);
        return toSessionView(session);
    }

    public List<AssistantSessionView> listSessions(String userId) {
        String uid = normalizeUserId(userId);
        List<AssistantSession> sessions = sessionMapper.selectList(
            new LambdaQueryWrapper<AssistantSession>()
                .eq(AssistantSession::getUserId, uid)
                .orderByDesc(AssistantSession::getUpdatedAt)
                .last("LIMIT 100")
        );

        List<AssistantSessionView> views = new ArrayList<AssistantSessionView>();
        for (AssistantSession session : sessions) {
            views.add(toSessionView(session));
        }
        return views;
    }

    public AssistantSessionDetailResponse getSessionDetail(String userId, String sessionId) {
        AssistantSession session = getSessionByUserAndId(userId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        List<AssistantMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<AssistantMessage>()
                .eq(AssistantMessage::getSessionId, sessionId)
                .orderByAsc(AssistantMessage::getCreatedAt)
                .last("LIMIT 300")
        );

        List<AssistantRun> runs = runMapper.selectList(
            new LambdaQueryWrapper<AssistantRun>()
                .eq(AssistantRun::getSessionId, sessionId)
                .orderByAsc(AssistantRun::getCreatedAt)
                .last("LIMIT 100")
        );

        List<String> runIds = new ArrayList<String>();
        for (AssistantRun run : runs) {
            runIds.add(run.getRunId());
        }

        List<AssistantArtifact> artifacts;
        if (CollectionUtils.isEmpty(runIds)) {
            artifacts = Collections.emptyList();
        } else {
            artifacts = artifactMapper.selectList(
                new LambdaQueryWrapper<AssistantArtifact>()
                    .in(AssistantArtifact::getRunId, runIds)
                    .orderByAsc(AssistantArtifact::getCreatedAt)
            );
        }

        AssistantSessionDetailResponse response = new AssistantSessionDetailResponse();
        response.setSession(toSessionView(session));
        response.setMessages(toMessageViews(messages));
        response.setRuns(toRunViews(runs));
        response.setArtifacts(toArtifactViews(artifacts));
        return response;
    }

    public AssistantMessageSubmitResponse submitMessage(String userId,
                                                        String sessionId,
                                                        AssistantMessageCreateRequest request) {
        String uid = normalizeUserId(userId);
        AssistantSession session = getSessionByUserAndId(uid, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new IllegalArgumentException("消息不能为空");
        }

        mergeSessionContext(session, request.getContext(), uid);

        AssistantMessage message = new AssistantMessage();
        message.setSessionId(sessionId);
        message.setRoleType("user");
        message.setContent(request.getContent().trim());
        messageMapper.insert(message);

        String runId = UUID.randomUUID().toString().replace("-", "");
        AssistantRun run = new AssistantRun();
        run.setRunId(runId);
        run.setSessionId(sessionId);
        run.setUserId(uid);
        run.setStatus(RUN_STATUS_QUEUED);
        run.setPolicyMode(session.getMode());
        run.setRequestContextJson(toJson(buildRequestContext(message, session, request.getContext())));
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        session.setLastMessageAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        String username = UserContextHolder.getCurrentUsername();
        graphOrchestrator.start(runId, uid, username);

        AssistantMessageSubmitResponse response = new AssistantMessageSubmitResponse();
        response.setSessionId(sessionId);
        response.setMessageId(message.getId());
        response.setRunId(runId);
        response.setStatus(run.getStatus());
        return response;
    }

    public AssistantRunView approveRun(String userId, String runId, AssistantApprovalRequest request) {
        String uid = normalizeUserId(userId);
        AssistantRun run = getRunByIdAndUser(runId, uid);
        if (run == null) {
            throw new IllegalArgumentException("运行不存在");
        }

        Boolean approved = request != null ? request.getApproved() : null;
        if (approved == null) {
            throw new IllegalArgumentException("请提供审批结果");
        }

        graphOrchestrator.approve(runId, uid, UserContextHolder.getCurrentUsername(), approved, request.getComment());

        AssistantRun latest = getRunByIdAndUser(runId, uid);
        return toRunView(latest);
    }

    public AssistantRunView cancelRun(String userId, String runId) {
        String uid = normalizeUserId(userId);
        AssistantRun run = getRunByIdAndUser(runId, uid);
        if (run == null) {
            throw new IllegalArgumentException("运行不存在");
        }

        graphOrchestrator.cancel(runId, uid);
        AssistantRun latest = getRunByIdAndUser(runId, uid);
        return toRunView(latest);
    }

    public AssistantSession getSessionByUserAndId(String userId, String sessionId) {
        return sessionMapper.selectOne(
            new LambdaQueryWrapper<AssistantSession>()
                .eq(AssistantSession::getSessionId, sessionId)
                .eq(AssistantSession::getUserId, normalizeUserId(userId))
                .last("LIMIT 1")
        );
    }

    public AssistantRun getRunByIdAndUser(String runId, String userId) {
        return runMapper.selectOne(
            new LambdaQueryWrapper<AssistantRun>()
                .eq(AssistantRun::getRunId, runId)
                .eq(AssistantRun::getUserId, normalizeUserId(userId))
                .last("LIMIT 1")
        );
    }

    private void mergeSessionContext(AssistantSession session, AssistantContextDTO requestContext, String userId) {
        if (requestContext == null) {
            return;
        }
        if (requestContext.getSourceId() != null) {
            session.setSourceId(requestContext.getSourceId());
        }
        if (StringUtils.hasText(requestContext.getDatabase())) {
            session.setDatabaseName(requestContext.getDatabase());
        }
        if (StringUtils.hasText(requestContext.getLimitProfile())) {
            session.setLimitProfile(requestContext.getLimitProfile());
        }
        if (requestContext.getManualLimit() != null) {
            session.setManualLimit(requestContext.getManualLimit());
        }

        String resolvedMode = policyService.resolveMode(requestContext.getMode(), userId);
        session.setMode(resolvedMode);
    }

    private Map<String, Object> buildRequestContext(AssistantMessage message,
                                                    AssistantSession session,
                                                    AssistantContextDTO requestContext) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("content", message.getContent());
        payload.put("sessionId", session.getSessionId());
        payload.put("messageId", message.getId());
        payload.put("context", buildContextPayload(session, requestContext));
        return payload;
    }

    private Map<String, Object> buildContextPayload(AssistantSession session, AssistantContextDTO requestContext) {
        AssistantContextDTO context = new AssistantContextDTO();
        context.setSourceId(session.getSourceId());
        context.setDatabase(session.getDatabaseName());
        context.setLimitProfile(session.getLimitProfile());
        context.setManualLimit(session.getManualLimit());
        context.setMode(session.getMode());
        context.setPlannerMode("nl2lf2sql");

        if (requestContext != null) {
            if (requestContext.getSourceId() != null) {
                context.setSourceId(requestContext.getSourceId());
            }
            if (StringUtils.hasText(requestContext.getDatabase())) {
                context.setDatabase(requestContext.getDatabase());
            }
            if (StringUtils.hasText(requestContext.getLimitProfile())) {
                context.setLimitProfile(requestContext.getLimitProfile());
            }
            if (requestContext.getManualLimit() != null) {
                context.setManualLimit(requestContext.getManualLimit());
            }
            if (StringUtils.hasText(requestContext.getMode())) {
                context.setMode(requestContext.getMode());
            }
            if (StringUtils.hasText(requestContext.getPlannerMode())) {
                context.setPlannerMode(requestContext.getPlannerMode());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sourceId", context.getSourceId());
        payload.put("database", context.getDatabase());
        payload.put("limitProfile", context.getLimitProfile());
        payload.put("manualLimit", context.getManualLimit());
        payload.put("mode", context.getMode());
        payload.put("plannerMode", context.getPlannerMode());
        return payload;
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : "anonymous";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    private AssistantSessionView toSessionView(AssistantSession session) {
        AssistantSessionView view = new AssistantSessionView();
        view.setSessionId(session.getSessionId());
        view.setTitle(session.getTitle());
        view.setMode(session.getMode());
        view.setStatus(session.getStatus());
        view.setCreatedAt(session.getCreatedAt());
        view.setUpdatedAt(session.getUpdatedAt());
        view.setLastMessageAt(session.getLastMessageAt());

        AssistantContextDTO context = new AssistantContextDTO();
        context.setSourceId(session.getSourceId());
        context.setDatabase(session.getDatabaseName());
        context.setLimitProfile(session.getLimitProfile());
        context.setManualLimit(session.getManualLimit());
        context.setMode(session.getMode());
        context.setPlannerMode("nl2lf2sql");
        view.setContext(context);
        return view;
    }

    private List<AssistantMessageView> toMessageViews(List<AssistantMessage> messages) {
        List<AssistantMessageView> views = new ArrayList<AssistantMessageView>();
        for (AssistantMessage message : messages) {
            AssistantMessageView view = new AssistantMessageView();
            view.setId(message.getId());
            view.setSessionId(message.getSessionId());
            view.setRunId(message.getRunId());
            view.setRole(message.getRoleType());
            view.setContent(message.getContent());
            view.setIntent(message.getIntent());
            view.setMetadataJson(message.getMetadataJson());
            view.setCreatedAt(message.getCreatedAt());
            views.add(view);
        }
        return views;
    }

    private List<AssistantRunView> toRunViews(List<AssistantRun> runs) {
        List<AssistantRunView> views = new ArrayList<AssistantRunView>();
        for (AssistantRun run : runs) {
            views.add(toRunView(run));
        }
        return views;
    }

    private AssistantRunView toRunView(AssistantRun run) {
        AssistantRunView view = new AssistantRunView();
        view.setRunId(run.getRunId());
        view.setSessionId(run.getSessionId());
        view.setStatus(run.getStatus());
        view.setIntent(run.getIntent());
        view.setPolicyMode(run.getPolicyMode());
        view.setErrorMessage(run.getErrorMessage());
        view.setStartedAt(run.getStartedAt());
        view.setCompletedAt(run.getCompletedAt());
        return view;
    }

    private List<AssistantArtifactView> toArtifactViews(List<AssistantArtifact> artifacts) {
        List<AssistantArtifactView> views = new ArrayList<AssistantArtifactView>();
        for (AssistantArtifact artifact : artifacts) {
            AssistantArtifactView view = new AssistantArtifactView();
            view.setId(artifact.getId());
            view.setRunId(artifact.getRunId());
            view.setArtifactType(artifact.getArtifactType());
            view.setTitle(artifact.getTitle());
            view.setContentJson(artifact.getContentJson());
            view.setCreatedAt(artifact.getCreatedAt());
            views.add(view);
        }
        return views;
    }
}
