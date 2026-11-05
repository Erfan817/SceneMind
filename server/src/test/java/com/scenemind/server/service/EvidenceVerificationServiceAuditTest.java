package com.scenemind.server.service;

import com.scenemind.core.evidence.RejectionReason;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring 适配层：旧 API 语义保持不变，新 API 能给出结构化原因与冲突信号。
 */
class EvidenceVerificationServiceAuditTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();

    private static VideoContext context() {
        return new VideoContext("offline://conflicting-deadline", "核验上线日期",
                List.of(new VideoContext.VideoSegment(60_000, 120_000,
                        "我们计划八月一日正式上线。",
                        List.of("正式上线：8月3日"),
                        List.of("frame_000075.jpg"))));
    }

    @Test
    void legacyBooleanApiKeepsOriginalSemantics() {
        AnalysisResult.Evidence valid = new AnalysisResult.Evidence(
                75_000, "ASR", "八月一日", "上线日期");
        assertTrue(service.supported(context(), valid));
        assertTrue(service.timestampCovered(context(), valid));
        assertTrue(service.supportsClaim(context(), "上线日期", valid));
        assertFalse(service.supportsClaim(context(), "别的结论", valid));
    }

    @Test
    void auditExplainsWhyEvidenceWasRejected() {
        AnalysisResult.Evidence spliced = new AnalysisResult.Evidence(
                75_000, "ASR+OCR", "上线。正式上线", "上线日期");
        assertFalse(service.supported(context(), spliced));
        assertEquals(RejectionReason.CROSS_CHANNEL_SPLICE,
                service.audit(context(), spliced).reason());
    }

    @Test
    void detectsAudioVisualConflict() {
        assertTrue(service.detectConflicts(context()).hasConflict());
        assertEquals(1, service.detectConflicts(context()).conflicts().size());
    }
}
