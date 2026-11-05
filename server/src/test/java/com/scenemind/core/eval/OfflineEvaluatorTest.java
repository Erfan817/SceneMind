package com.scenemind.core.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineEvaluatorTest {

    @Test
    void rrfKeepsRankingStableUnderScoreRescaling() {
        OfflineEvaluator.Report report = OfflineEvaluator.evaluate();
        assertEquals(1.0, report.hybrid().rankStability(), 1e-9,
                "RRF 只依赖名次，单调缩放后 TopK 集合必须不变");
        assertTrue(report.baseline().rankStability() < 1.0,
                "线性加权在量纲变化时应出现排序漂移");
    }

    @Test
    void hybridDoesNotRegressOnAnyMetric() {
        OfflineEvaluator.Report report = OfflineEvaluator.evaluate();
        assertTrue(report.hybrid().recallAtK() >= report.baseline().recallAtK());
        assertTrue(report.hybrid().temporalCoverage() >= report.baseline().temporalCoverage());
        assertTrue(report.hybrid().redundancyRate() <= report.baseline().redundancyRate());
    }

    @Test
    void evaluationIsFullyDeterministic() {
        OfflineEvaluator.Report first = OfflineEvaluator.evaluate();
        OfflineEvaluator.Report second = OfflineEvaluator.evaluate();
        assertEquals(first.baseline(), second.baseline());
        assertEquals(first.hybrid(), second.hybrid());
        assertEquals(first.diagnostics(), second.diagnostics());
    }

    @Test
    void metricsHandleEdgeCases() {
        assertEquals(1.0, EvaluationMetrics.rankStability(java.util.Set.of(), java.util.Set.of()));
        assertEquals(0.0, EvaluationMetrics.redundancyRate(java.util.List.of(), 300_000L, 0.8));
        assertEquals(1.0, EvaluationMetrics.recallAtK(java.util.List.of(), java.util.Set.of()));
    }
}
