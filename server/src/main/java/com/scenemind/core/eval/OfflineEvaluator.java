package com.scenemind.core.eval;

import com.scenemind.core.eval.BenchmarkFixtures.Scenario;
import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.LinearBaselineRanker;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.RankingDiagnostics;
import com.scenemind.core.ranking.RankingResult;

import java.util.List;
import java.util.Set;

/**
 * 完全离线的策略 A/B 评测：不启动 Spring、不连数据库/向量库、不调用任何 LLM。
 *
 * <p>改造前的 {@code OfflineAgentEvaluationRunner} 虽然叫「Offline」，实际仍会走
 * {@code AiService.callJson(..., useVision=true, "")}，必须配置 API Key 且结果不可复现。
 * 这里把「检索策略」这一层单独抽出来做确定性回放，让排序改动可以进 CI 门禁。</p>
 */
public final class OfflineEvaluator {

    /** 一次完整 A/B 的结论。 */
    public record Report(
            String dataset,
            int candidateCount,
            int relevantCount,
            EvaluationMetrics.Metrics baseline,
            EvaluationMetrics.Metrics hybrid,
            RankingDiagnostics diagnostics
    ) {
    }

    private OfflineEvaluator() {
    }

    public static Report evaluate() {
        Scenario scenario = BenchmarkFixtures.scenarios().get(0);
        List<Candidate> base = scenario.candidates();
        Set<String> relevant = scenario.relevantIds();
        int topK = BenchmarkFixtures.TOP_K;
        long windowMs = BenchmarkFixtures.WINDOW_MS;

        RankingConfig config = RankingConfig.defaults();
        LinearBaselineRanker linear = new LinearBaselineRanker();
        HybridRanker hybrid = new HybridRanker(config);

        List<Candidate> baselineTop = linear.rank(base, topK);
        List<Candidate> baselineTopPerturbed =
                linear.rank(BenchmarkFixtures.perturbSemantic(base, 37.0, 5.0), topK);

        RankingResult hybridResult = hybrid.rank(BenchmarkFixtures.channelRankings(base));
        RankingResult hybridPerturbed =
                hybrid.rank(BenchmarkFixtures.channelRankings(
                        BenchmarkFixtures.perturbSemantic(base, 37.0, 5.0)));
        List<Candidate> hybridTop = hybridResult.candidates();

        EvaluationMetrics.Metrics baselineMetrics = new EvaluationMetrics.Metrics(
                EvaluationMetrics.recallAtK(baselineTop, relevant),
                EvaluationMetrics.temporalCoverage(baselineTop, relevant, windowMs),
                EvaluationMetrics.redundancyRate(baselineTop, windowMs,
                        config.redundancySimilarity()),
                EvaluationMetrics.rankStability(
                        EvaluationMetrics.idsOf(baselineTop),
                        EvaluationMetrics.idsOf(baselineTopPerturbed)));

        EvaluationMetrics.Metrics hybridMetrics = new EvaluationMetrics.Metrics(
                EvaluationMetrics.recallAtK(hybridTop, relevant),
                EvaluationMetrics.temporalCoverage(hybridTop, relevant, windowMs),
                EvaluationMetrics.redundancyRate(hybridTop, windowMs,
                        config.redundancySimilarity()),
                EvaluationMetrics.rankStability(
                        EvaluationMetrics.idsOfItems(hybridResult.items()),
                        EvaluationMetrics.idsOfItems(hybridPerturbed.items())));

        return new Report(scenario.name(), base.size(), relevant.size(),
                baselineMetrics, hybridMetrics, hybridResult.diagnostics());
    }
}
