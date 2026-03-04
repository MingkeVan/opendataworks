package com.onedata.portal.service.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantStreamService {

    private static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int MAX_REPLAY_EVENTS = 200;

    private final ObjectMapper objectMapper;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitterMap = new ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>();
    private final Map<String, Deque<String>> replayMap = new ConcurrentHashMap<String, Deque<String>>();

    public SseEmitter openStream(String runId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        emitterMap.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<SseEmitter>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> {
            removeEmitter(runId, emitter);
            emitter.complete();
        });
        emitter.onError((ex) -> {
            removeEmitter(runId, emitter);
        });

        replayTo(runId, emitter);
        return emitter;
    }

    public void sendEvent(String runId, String eventType, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("event", eventType);
        envelope.put("runId", runId);
        envelope.put("at", LocalDateTime.now().toString());
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            Object uiType = map.get("uiType");
            Object phase = map.get("phase");
            Object summary = map.get("summary");
            if (uiType != null) {
                envelope.put("uiType", uiType);
            }
            if (phase != null) {
                envelope.put("phase", phase);
            }
            if (summary != null) {
                envelope.put("summary", summary);
            }
        }
        envelope.put("data", data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.warn("Serialize assistant event failed, runId={}, event={}", runId, eventType, e);
            payload = "{\"event\":\"" + eventType + "\",\"runId\":\"" + runId + "\",\"data\":{\"message\":\"serialize_failed\"}}";
        }

        appendReplay(runId, payload);

        List<SseEmitter> emitters = emitterMap.get(runId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> dead = new ArrayList<SseEmitter>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("message")
                    .data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
        }
    }

    public void complete(String runId) {
        List<SseEmitter> emitters = emitterMap.remove(runId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private void replayTo(String runId, SseEmitter emitter) {
        Deque<String> events = replayMap.get(runId);
        if (events == null || events.isEmpty()) {
            return;
        }
        for (String payload : events) {
            try {
                emitter.send(SseEmitter.event()
                    .name("message")
                    .data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                break;
            }
        }
    }

    private void appendReplay(String runId, String payload) {
        Deque<String> queue = replayMap.computeIfAbsent(runId, key -> new ArrayDeque<String>());
        synchronized (queue) {
            queue.addLast(payload);
            while (queue.size() > MAX_REPLAY_EVENTS) {
                queue.pollFirst();
            }
        }
    }

    private void removeEmitter(String runId, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterMap.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emitterMap.remove(runId);
        }
    }
}
