package com.scenemind.core.eval;

import com.scenemind.core.eval.CitationMetrics.Citation;
import com.scenemind.core.eval.CitationMetrics.Claim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitationMetricsTest {

    private static EvidenceCorpus corpus() {
        return new EvidenceCorpus(List.of(
                new EvidenceSegment(0, 60_000,
                        "我们计划八月一日正式上线。", List.of("正式上线：8月3日")),
                new EvidenceSegment(60_000, 120_000,
                        "前序遍历先访问根节点，然后递归访问左子树。",
                        List.of("前序遍历：根节点、左子树、右子树")),
                new EvidenceSegment(120_000, 180_000,
                        "构建命令是 npm run build。", List.of("npm run build"))));
    }

    @Test
    void computesPrecisionRecallAndF1() {
        CitationMetrics metrics = CitationMetrics.evaluate(corpus(), List.of(
                new Claim("c1", "上线日期",
                        List.of(new Citation(10_000, "ASR", "八月一日"))),
                new Claim("c2", "前序遍历顺序",
                        List.of(new Citation(70_000, "OCR", "根节点、左子树、右子树"))),
                new Claim("c3", "构建命令",
                        List.of(new Citation(130_000, "ASR", "npm run build"))),
                new Claim("c4", "负责人",
                        List.of(new Citation(20_000, "ASR", "李四")))));

        assertEquals(4, metrics.evidenceTotal());
        assertEquals(3, metrics.evidenceSupported());
        assertEquals(4, metrics.claimTotal());
        assertEquals(3, metrics.claimSupported());
        assertEquals(0.75, metrics.precision(), 1e-9);
        assertEquals(0.75, metrics.recall(), 1e-9);
        assertEquals(0.75, metrics.f1(), 1e-9);
    }

    @Test
    void rejectsCitationsThatOnlyExistAcrossChannels() {
        CitationMetrics metrics = CitationMetrics.evaluate(corpus(), List.of(
                new Claim("splice", "最终上线日期",
                        List.of(new Citation(10_000, "ASR+OCR", "上线。正式上线")))));

        assertEquals(1, metrics.evidenceTotal());
        assertEquals(0, metrics.evidenceSupported());
        assertEquals(0, metrics.claimSupported());
        assertEquals(0.0, metrics.precision(), 1e-9);
        assertEquals(0.0, metrics.recall(), 1e-9);
    }

    @Test
    void emptyInputDoesNotReportPerfectScores() {
        CitationMetrics metrics = CitationMetrics.evaluate(corpus(), List.of());
        assertEquals(0, metrics.evidenceTotal());
        assertEquals(0, metrics.claimTotal());
        assertEquals(0.0, metrics.precision(), 1e-9);
        assertEquals(0.0, metrics.recall(), 1e-9);
        assertEquals(0.0, CitationMetrics.f1(0, 0), 1e-9);
        assertEquals(0.0, CitationMetrics.f1(1, 0), 1e-9);
        assertEquals(1.0, CitationMetrics.f1(1, 1), 1e-9);
    }
}
