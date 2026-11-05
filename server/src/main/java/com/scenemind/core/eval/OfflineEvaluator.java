package com.scenemind.core.eval;

import com.scenemind.core.eval.BenchmarkFixtures.Scenario;
import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.LinearBaselineRanker;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.RankingDiagnostics;
import com.scenemind.core.ranking.RankingResult;
import com.scenemind.core.ranking.TopKPolicy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 完全离线的策略 A/B 评测：不启动 Spring、不连数据库/向量库、不调用任何 LLM。
 *
 * <p>改造前的 {@code OfflineAgentEvaluationRunner} 虽然叫「Offline」，实际仍会走
 * {@code AiService.callJson(..., useVision=true, "")}，必须配置 API Key 且结果不可复现。
 * 这里把「检索策略」这一层单独抽出来做确定性回放，让排序改动可以进 CI 门禁。</p>
 *
 * <p><b>与在线同链路</b>：评测走 {@code fuse() -> TopKPolicy.decide() -> select()}，
 * 与 {@code VideoEvidenceRetrievalService} 一致，参数由 {@link RankingConfig} 与
 * {@link TopKPolicy} 注入（入口从与 Spring 同名的 {@code SCENEMIND_*} 环境变量读取）。
 * 因此调参会同时改变线上排序与离线报告，CI 的报告 diff 门禁才真正覆盖配置变更。</p>
 */
public final class OfflineEvaluator {

    /** 一次完整 A/B 的结论。 */
    public record Report(
            String dataset,
            int candidateCount,
            int relevantCount,
            EvaluationMetrics.Metrics baseline,
            EvaluationMetrics.Metrics hybrid,
            RankingDiagnostics diagnostics,
            RankingConfig config,
            TopKPolicy.Decision decision
    ) {
    }

    private OfflineEvaluator() {
    }

    /** 默认参数入口：与生产默认值一致。 */
    public static Report evaluate() {
        return evaluate(RankingConfig.defaults(), new TopKPolicy());
    }

    /** 配置驱动入口：在线与离线共用同一套参数与同一条排序链路。 */
    public static Report evaluate(RankingConfig config, TopKPolicy policy) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(policy, "policy");
        Scenario scenario = BenchmarkFixtures.scenarios().get(0);
        List<Candidate> base = scenario.candidates();
        Set<String> relevant = scenario.relevantIds();
        int topK = config.maxResults();
        long windowMs = config.windowMs();

        LinearBaselineRanker linear = new LinearBaselineRanker();
        HybridRanker hybrid = new HybridRanker(config);

        List<Candidate> baselineTop = linear.rank(base, topK);
        List<Candidate> baselineTopPerturbed =
                linear.rank(BenchmarkFixtures.perturbSemantic(base, 37.0, 5.0), topK);

        HybridRanker.Fusion fusion = hybrid.fuse(BenchmarkFixtures.channelRankings(base));
        TopKPolicy.Decision decision = policy.decide(fusion.items(), config.maxResults());
        RankingResult hybridResult = hybrid.select(fusion, decision.topK());

        List<Candidate> perturbed = BenchmarkFixtures.perturbSemantic(base, 37.0, 5.0);
        HybridRanker.Fusion perturbedFusion =
                hybrid.fuse(BenchmarkFixtures.channelRankings(perturbed));
        TopKPolicy.Decision perturbedDecision =
                policy.decide(perturbedFusion.items(), config.maxResults());
        RankingResult hybridPerturbed = hybrid.select(perturbedFusion, perturbedDecision.topK());
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
                baselineMetrics, hybridMetrics, hybridResult.diagnostics(), config, decision);
    }
}
