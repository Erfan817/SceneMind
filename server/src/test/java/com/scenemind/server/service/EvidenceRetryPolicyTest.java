package com.scenemind.server.service;

import com.scenemind.server.dto.EvidenceAuditReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRetryPolicyTest {

    private static EvidenceAuditReport.EvidenceFinding finding(
            long timestampMs, boolean accepted, String reason, boolean retryable) {
        return new EvidenceAuditReport.EvidenceFinding(
                timestampMs, accepted, reason, retryable, accepted ? 1.0 : 0.2, "ASR");
    }

    private static EvidenceAuditReport report(List<EvidenceAuditReport.EvidenceFinding> findings) {
        int accepted = (int) findings.stream().filter(EvidenceAuditReport.EvidenceFinding::accepted).count();
        int retryable = (int) findings.stream().filter(f -> !f.accepted() && f.retryable()).count();
        int hallucination = (int) findings.stream().filter(f -> !f.accepted() && !f.retryable()).count();
        return new EvidenceAuditReport(findings.size(), accepted, retryable, hallucination,
                findings, List.of(), List.of());
    }

    @Test
    void allEvidenceAcceptedNeedsNoRetry() {
        EvidenceRetryPolicy.Decision decision = EvidenceRetryPolicy.decide(report(List.of(
                finding(10_000, true, "NONE", false),
                finding(20_000, true, "NONE", false))));
        assertEquals(EvidenceRetryPolicy.Action.NONE, decision.action());
        assertTrue(decision.retryTimestamps().isEmpty());
    }

    @Test
    void retryableRejectionsCollectTimestampsForTargetedRefresh() {
        EvidenceRetryPolicy.Decision decision = EvidenceRetryPolicy.decide(report(List.of(
                finding(120_000, false, "TIMESTAMP_OUT_OF_RANGE", true),
                finding(240_000, false, "TEXT_NOT_FOUND", true),
                finding(300_000, true, "NONE", false))));
        assertEquals(EvidenceRetryPolicy.Action.REFRESH_EVIDENCE, decision.action());
        assertEquals(List.of(120_000L, 240_000L), decision.retryTimestamps());
        assertTrue(decision.feedback().stream().anyMatch(line -> line.contains("TIMESTAMP_OUT_OF_RANGE")));
    }

    @Test
    void hallucinationOnlySkipsEvidenceRefresh() {
        EvidenceRetryPolicy.Decision decision = EvidenceRetryPolicy.decide(report(List.of(
                finding(75_000, false, "CROSS_CHANNEL_SPLICE", false),
                finding(80_000, false, "UNKNOWN_SOURCE", false))));
        assertEquals(EvidenceRetryPolicy.Action.REWRITE_ONLY, decision.action());
        assertTrue(decision.retryTimestamps().isEmpty(),
                "伪造来源 / 跨通道拼接是幻觉，不该再烧一轮检索");
        assertTrue(decision.feedback().stream().anyMatch(line -> line.contains("疑似幻觉")));
    }

    @Test
    void mixedRejectionsOnlyRetryTheRepairableOnes() {
        EvidenceRetryPolicy.Decision decision = EvidenceRetryPolicy.decide(report(List.of(
                finding(120_000, false, "TIMESTAMP_OUT_OF_RANGE", true),
                finding(75_000, false, "CROSS_CHANNEL_SPLICE", false))));
        assertEquals(EvidenceRetryPolicy.Action.REFRESH_EVIDENCE, decision.action());
        assertEquals(List.of(120_000L), decision.retryTimestamps());
        assertFalse(decision.retryTimestamps().contains(75_000L),
                "幻觉项不进入定向补检索名单");
    }

    @Test
    void emptyReportIsSafe() {
        assertEquals(EvidenceRetryPolicy.Action.NONE,
                EvidenceRetryPolicy.decide(report(List.of())).action());
        assertEquals(EvidenceRetryPolicy.Action.NONE,
                EvidenceRetryPolicy.decide(null).action());
    }
}
