package com.scenemind.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * trace 契约测试：前端调试面板依赖 {@code diagnostics} 与 {@code conflicts} 两个字段，
 * 这里锁住结构，避免以后改埋点时悄悄把面板弄空。
 */
class AgentTelemetryTest {

    @SuppressWarnings("unchecked")
    private static AgentTelemetry telemetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return new AgentTelemetry(redis, new ObjectMapper());
    }

    @Test
    void snapshotCarriesStructuredDiagnosticsAndConflicts() {
        AgentTelemetry telemetry = telemetry();
        telemetry.start(7L, "核验上线日期");
        telemetry.sectionCurrent("retrieval", Map.of("topK", 8, "windowsCovered", 3));
        telemetry.sectionCurrent("retry", Map.of("action", "REWRITE_ONLY"));
        telemetry.conflictsCurrent(List.of(Map.of(
                "startMs", 60_000, "asrValue", "8月1日", "ocrValue", "8月3日")));

        Map<String, Object> trace = telemetry.latest(7L, "核验上线日期");

        assertTrue(trace.containsKey("diagnostics"), "trace 必须带 diagnostics");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) trace.get("diagnostics");
        assertEquals(Map.of("topK", 8, "windowsCovered", 3), diagnostics.get("retrieval"));
        assertEquals(Map.of("action", "REWRITE_ONLY"), diagnostics.get("retry"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) trace.get("conflicts");
        assertEquals(1, conflicts.size());
        assertEquals("8月1日", conflicts.get(0).get("asrValue"));
    }

    @Test
    void currentSectionIsReadableWithinTheSameThread() {
        AgentTelemetry telemetry = telemetry();
        telemetry.start(9L, "goal");
        telemetry.sectionCurrent("retry", Map.of("action", "REFRESH_EVIDENCE"));

        Object section = telemetry.currentSection("retry");
        assertTrue(section instanceof Map<?, ?>);
        assertEquals("REFRESH_EVIDENCE", ((Map<?, ?>) section).get("action"));
    }

    @Test
    void sectionByTraceIdWorksFromAnotherThread() throws Exception {
        AgentTelemetry telemetry = telemetry();
        String traceId = telemetry.start(13L, "goal");
        Thread branch = new Thread(() -> telemetry.section(
                traceId, "frames", Map.of("mode", "dense", "kept", 22)));
        branch.start();
        branch.join();

        @SuppressWarnings("unchecked")
        Map<String, Object> trace = telemetry.latest(13L, "goal");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostics = (Map<String, Object>) trace.get("diagnostics");
        assertEquals(Map.of("mode", "dense", "kept", 22), diagnostics.get("frames"));
    }

    @Test
    void snapshotWithoutDiagnosticsStillHasEmptySections() {
        AgentTelemetry telemetry = telemetry();
        telemetry.start(11L, "goal");
        Map<String, Object> trace = telemetry.latest(11L, "goal");

        assertTrue(((Map<?, ?>) trace.get("diagnostics")).isEmpty());
        assertEquals(List.of(), trace.get("conflicts"));
    }
}
