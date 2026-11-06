package com.scenemind.server.service;

import com.scenemind.core.eval.CitationMetrics;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerificationServiceTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();

    @Test
    void rejectsUnknownSourcesEvenWhenTheyContainAValidSourceName() {
        for (String source : List.of("NOT_ASR", "OCR_GUESS", "ASR+WEB", "UNKNOWN_ASR_OCR")) {
            assertFalse(service.supported(context, new AnalysisResult.Evidence(
                    125_000, source, "前序遍历", "遍历")), source);
        }
    }

    @Test
    void acceptsOnlyDeclaredSourcesAndHonorsExclusiveSegmentEnd() {
        for (String source : List.of("ASR", "OCR", "ASR+OCR", "asr")) {
            assertTrue(service.supported(context, new AnalysisResult.Evidence(
                    120_000, source, "前序遍历", "遍历")), source);
            assertFalse(service.supported(context, new AnalysisResult.Evidence(
                    180_000, source, "前序遍历", "遍历")), source);
        }
    }
    private final VideoContext context = new VideoContext(
            "lesson.mp4",
            "总结课程",
            List.of(new VideoContext.VideoSegment(
                    120_000,
                    180_000,
                    "接下来讲解二叉树的前序遍历",
                    List.of("前序遍历：根节点、左子树、右子树"),
                    List.of("frame_000125.jpg"))));

    @Test
    void acceptsVerbatimEvidenceAtTheDeclaredTimestamp() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supported(context, evidence));
        assertTrue(service.supportsClaim(context, "前序遍历顺序", evidence));
    }

    @Test
    void computesStructuredCitationMetricsFromClaimsAndEvidenceIds() {
        AnalysisResult result = new AnalysisResult(
                "标题",
                List.of("前序遍历顺序"),
                List.of(new AnalysisResult.Evidence(
                        125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序", "e1")),
                List.of(),
                List.of(),
                List.of(new AnalysisResult.Claim("c1", "前序遍历顺序", List.of("e1"))));

        CitationMetrics metrics = service.citationMetrics(context, result);

        assertNotNull(metrics);
        assertEquals(1, metrics.evidenceTotal());
        assertEquals(1, metrics.evidenceSupported());
        assertEquals(1, metrics.claimTotal());
        assertEquals(1, metrics.claimSupported());
        assertEquals(1.0, metrics.f1(), 1e-9);
    }

    @Test
    void rejectsTextThatOnlyLooksSimilarToTheSource() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点左子树不存在，因此应跳过", "前序遍历顺序");

        assertFalse(service.supported(context, evidence));
    }
}
