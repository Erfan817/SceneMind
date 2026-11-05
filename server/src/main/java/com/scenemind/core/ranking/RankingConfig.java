package com.scenemind.core.ranking;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 融合检索策略的全部可调参数。
 *
 * <p>把策略从服务里的 {@code private static final} 常量提升为显式配置对象，
 * 目的是让 A/B 评测、按场景调参和回归测试都能在不改代码的前提下进行。</p>
 *
 * @param rrfK                      RRF 的秩偏置常数，越大越弱化头部优势（常用 60）
 * @param channelWeights            各通道贡献权重，只缩放「秩」不缩放原始分数
 * @param windowMs                  时间窗宽度：既是覆盖统计的桶，也是冗余判定的距离阈值
 * @param redundancySimilarity     文本 bigram 相似度阈值，超过且时间相邻才判为冗余
 * @param maxResults                最终返回条数
 */
public record RankingConfig(
        int rrfK,
        Map<RetrievalChannel, Double> channelWeights,
        long windowMs,
        double redundancySimilarity,
        int maxResults
) {
    public RankingConfig {
        if (rrfK <= 0) throw new IllegalArgumentException("rrfK must be positive");
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        if (maxResults <= 0) throw new IllegalArgumentException("maxResults must be positive");
        if (redundancySimilarity < 0 || redundancySimilarity > 1) {
            throw new IllegalArgumentException("redundancySimilarity must be within [0,1]");
        }
        Objects.requireNonNull(channelWeights, "channelWeights");
        EnumMap<RetrievalChannel, Double> copy = new EnumMap<>(RetrievalChannel.class);
        channelWeights.forEach((channel, weight) -> {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(weight, "weight");
            if (weight < 0) throw new IllegalArgumentException("channel weight must be non-negative");
            copy.put(channel, weight);
        });
        channelWeights = Map.copyOf(copy);
    }

    /** 生产默认：三通道等权，5 分钟时间窗，语义稍加权。 */
    public static RankingConfig defaults() {
        EnumMap<RetrievalChannel, Double> weights = new EnumMap<>(RetrievalChannel.class);
        weights.put(RetrievalChannel.SEMANTIC, 1.0);
        weights.put(RetrievalChannel.LEXICAL, 1.0);
        weights.put(RetrievalChannel.VISUAL, 0.8);
        return new RankingConfig(60, weights, 300_000L, 0.82, 8);
    }

    public double weightOf(RetrievalChannel channel) {
        return channelWeights.getOrDefault(channel, 0.0);
    }
}
