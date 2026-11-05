package com.scenemind.core.ranking;

import java.util.Comparator;
import java.util.List;

/**
 * 改造前的排序策略，作为离线评测的 baseline 保留。
 *
 * <p>原实现把三个通道的原始分数按固定权重线性相加：
 * {@code 0.6 * semantic + 0.25 * lexical + 0.15 * visual}。
 * 问题在于 semantic 可能是 Qdrant 返回的 [0,1] 分数，也可能是本地 cosine 的 [-1,1]，
 * 而 lexical / visual 是 [0,1] 的命中率；量纲和分布都不一致时，权重就不再代表真实重要性。</p>
 *
 * <p>该类故意保留旧行为，让 A/B 评测可以量化改造收益，而不是靠主观描述。</p>
 */
public final class LinearBaselineRanker {

    public static final double W_SEMANTIC = 0.6;
    public static final double W_LEXICAL = 0.25;
    public static final double W_VISUAL = 0.15;

    public double score(Candidate candidate) {
        return W_SEMANTIC * candidate.semanticScore()
                + W_LEXICAL * candidate.lexicalScore()
                + W_VISUAL * candidate.visualScore();
    }

    public List<Candidate> rank(List<Candidate> candidates, int topK) {
        if (topK <= 0) return List.of();
        return candidates.stream()
                .sorted(Comparator.comparingDouble(this::score).reversed()
                        .thenComparing(Candidate::startMs)
                        .thenComparing(Candidate::id))
                .limit(topK)
                .toList();
    }
}
