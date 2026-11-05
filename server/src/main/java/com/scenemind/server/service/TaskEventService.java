package com.scenemind.server.service;

import com.scenemind.server.dto.AnalysisMode;
import com.scenemind.server.dto.TaskEvent;
import com.scenemind.server.dto.TaskStatus;
import com.scenemind.server.dto.TaskStage;
import com.scenemind.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 后端阶段变化发布到这里，SSE 订阅者只关心事件，不反向依赖业务服务。 */
@Service
public class TaskEventService implements MessageListener {

    public static final String ANALYSIS = "analysis";
    public static final String TRANSCRIPTION = "transcription";
    public static final String REDIS_CHANNEL = "dovideo:task-events";

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    /** 每个任务保留的最近事件数，用于浏览器带 Last-Event-ID 重连时补发。 */
    private static final int MAX_HISTORY_EVENTS = 200;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscribers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EventLog> eventLogs = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                TaskStatus initialStatus,
                                TaskStage stage) {
        return subscribe(mediaId, type, goal, AnalysisMode.GENERAL, initialStatus, stage, null);
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                TaskStatus initialStatus,
                                TaskStage stage,
                                String lastEventId) {
        return subscribe(mediaId, type, goal, AnalysisMode.GENERAL, initialStatus, stage, lastEventId);
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                AnalysisMode mode,
                                TaskStatus initialStatus,
                                TaskStage stage) {
        return subscribe(mediaId, type, goal, mode, initialStatus, stage, null);
    }

    public SseEmitter subscribe(Long mediaId,
                                String type,
                                String goal,
                                AnalysisMode mode,
                                TaskStatus initialStatus,
                                TaskStage stage,
                                String lastEventId) {
        return subscribe(mediaId, type, goal, mode,
                () -> TaskEvent.of(initialStatus, stage), lastEventId);
    }

    public SseEmitter subscribe(Long mediaId, String type, String goal, AnalysisMode mode,
                                Supplier<TaskEvent> initialEvent) {
        return subscribe(mediaId, type, goal, mode, initialEvent, null);
    }

    /** Register before querying state so completion during the query cannot fall into a gap. */
    public SseEmitter subscribe(Long mediaId, String type, String goal, AnalysisMode mode,
                                Supplier<TaskEvent> initialEvent, String lastEventId) {
        String key = key(mediaId, type, goal, mode);
        EventLog eventLog = eventLogs.computeIfAbsent(key, ignored -> new EventLog());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscription subscription = new Subscription(emitter);
        emitter.onCompletion(() -> remove(key, subscription));
        emitter.onTimeout(() -> remove(key, subscription));
        emitter.onError(error -> remove(key, subscription));
        // Registration and removal must use the same per-key map operation. Otherwise the last
        // disconnect can remove an empty list between computeIfAbsent and add, orphaning this stream.
        subscribers.compute(key, (ignored, emitters) -> {
            CopyOnWriteArrayList<Subscription> current =
                    emitters == null ? new CopyOnWriteArrayList<>() : emitters;
            current.add(subscription);
            return current;
        });
        try {
            // 浏览器重连时优先补发 Last-Event-ID 之后的事件；没有可补发事件时退回当前快照。
            List<SequencedEvent> replay = eventLog.after(lastEventId);
            if (!replay.isEmpty()) {
                for (SequencedEvent sequenced : replay) {
                    send(key, subscription, sequenced.event(), false, sequenced.seq());
                }
            } else {
                send(key, subscription, initialEvent.get(), true, eventLog.current());
            }
        } catch (RuntimeException error) {
            remove(key, subscription);
            emitter.completeWithError(error);
            throw error;
        }
        return emitter;
    }

    public void publishAnalysis(Long mediaId, String goal, TaskStatus status, TaskStage stage) {
        publishAnalysis(mediaId, goal, AnalysisMode.GENERAL, status, stage);
    }

    public void publishAnalysis(Long mediaId,
                                String goal,
                                AnalysisMode mode,
                                TaskStatus status,
                                TaskStage stage) {
        publish(key(mediaId, ANALYSIS, goal, mode), TaskEvent.of(status, stage));
    }

    public void publishTranscription(Long mediaId, TaskStatus status, TaskStage stage) {
        publish(key(mediaId, TRANSCRIPTION, "", AnalysisMode.GENERAL), TaskEvent.of(status, stage));
    }

    private void publish(String key, TaskEvent event) {
        try {
            String payload = objectMapper.createObjectNode()
                    .put("key", key)
                    .set("event", objectMapper.valueToTree(event))
                    .toString();
            Long receivers = redisTemplate.convertAndSend(REDIS_CHANNEL, payload);
            if (receivers == null || receivers == 0) publishLocal(key, event);
        } catch (RuntimeException e) {
            log.warn("task_event_redis_publish_failed key={}", key, e);
            publishLocal(key, event);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = objectMapper.readTree(message.getBody());
            publishLocal(
                    payload.path("key").asText(),
                    objectMapper.treeToValue(payload.path("event"), TaskEvent.class));
        } catch (Exception e) {
            log.warn("task_event_redis_message_invalid", e);
        }
    }

    private void publishLocal(String key, TaskEvent event) {
        EventLog eventLog = eventLogs.computeIfAbsent(key, ignored -> new EventLog());
        long seq = eventLog.next();
        eventLog.append(seq, event);
        List<Subscription> emitters = subscribers.get(key);
        if (emitters == null) return;
        emitters.forEach(subscription -> send(key, subscription, event, false, seq));
    }

    private void send(String key, Subscription subscription, TaskEvent event,
                      boolean initial, Long eventId) {
        synchronized (subscription) {
            if (subscription.closed || (initial && subscription.liveEventDelivered)) return;
            if (!initial) subscription.liveEventDelivered = true;
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event()
                        .name("task-status")
                        .data(event);
                if (eventId != null) builder.id(String.valueOf(eventId));
                subscription.emitter.send(builder);
                if (event.terminal()) {
                    remove(key, subscription);
                    subscription.emitter.complete();
                }
            } catch (IOException | RuntimeException e) {
                remove(key, subscription);
                subscription.emitter.completeWithError(e);
                log.debug("task_event_stream_closed key={}", key);
            }
        }
    }

    private void remove(String key, Subscription subscription) {
        synchronized (subscription) {
            subscription.closed = true;
            subscribers.computeIfPresent(key, (ignored, emitters) -> {
                emitters.remove(subscription);
                return emitters.isEmpty() ? null : emitters;
            });
        }
    }

    private static final class Subscription {
        private final SseEmitter emitter;
        private boolean liveEventDelivered;
        private boolean closed;

        private Subscription(SseEmitter emitter) { this.emitter = emitter; }
    }

    /** 单任务的事件序列 + 有界历史（单机部署；重启后历史丢失，但快照仍可用）。 */
    private static final class EventLog {
        private final AtomicLong sequence = new AtomicLong();
        private final Deque<SequencedEvent> events = new ArrayDeque<>();

        private long next() {
            return sequence.incrementAndGet();
        }

        private synchronized void append(long seq, TaskEvent event) {
            events.addLast(new SequencedEvent(seq, event));
            while (events.size() > MAX_HISTORY_EVENTS) {
                events.removeFirst();
            }
        }

        private synchronized List<SequencedEvent> after(String lastEventId) {
            if (lastEventId == null || lastEventId.isBlank()) return List.of();
            long threshold;
            try {
                threshold = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException e) {
                return List.of();
            }
            List<SequencedEvent> replay = new ArrayList<>();
            for (SequencedEvent event : events) {
                if (event.seq() > threshold) replay.add(event);
            }
            return replay;
        }

        private long current() {
            return sequence.get();
        }
    }

    private record SequencedEvent(long seq, TaskEvent event) {
    }

    private String key(Long mediaId, String type, String goal, AnalysisMode mode) {
        String suffix = ANALYSIS.equals(type)
                ? AnalysisTaskKeys.goalDigest(goal, mode)
                : "default";
        return type + ":" + mediaId + ":" + suffix;
    }
}
