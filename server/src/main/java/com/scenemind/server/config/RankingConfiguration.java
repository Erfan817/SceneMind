package com.scenemind.server.config;

import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.RetrievalChannel;
import com.scenemind.core.ranking.TopKPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把检索策略参数提升为外部配置。
 *
 * <p>改造前 {@code TOP_K}、{@code 0.6/0.25/0.15} 这些策略常量硬编码在服务类里，
 * 想做一次 A/B 或按视频类型调参都得改代码重新发版。这里统一收敛到
 * {@code scenemind.ranking.*}，全部提供默认值，环境变量可覆盖。</p>
 */
@Configuration
public class RankingConfiguration {

    @Bean
    public RankingConfig rankingConfig(
            @Value("${scenemind.ranking.rrf-k:60}") int rrfK,
            @Value("${scenemind.ranking.window-ms:300000}") long windowMs,
            @Value("${scenemind.ranking.redundancy-similarity:0.82}") double similarity,
            @Value("${scenemind.ranking.max-results:8}") int maxResults,
            @Value("${scenemind.ranking.weight-semantic:1.0}") double semanticWeight,
            @Value("${scenemind.ranking.weight-lexical:1.0}") double lexicalWeight,
            @Value("${scenemind.ranking.weight-visual:0.8}") double visualWeight) {
        Map<RetrievalChannel, Double> weights = new EnumMap<>(RetrievalChannel.class);
        weights.put(RetrievalChannel.SEMANTIC, semanticWeight);
        weights.put(RetrievalChannel.LEXICAL, lexicalWeight);
        weights.put(RetrievalChannel.VISUAL, visualWeight);
        return new RankingConfig(rrfK, weights, windowMs, similarity, maxResults);
    }

    @Bean
    public HybridRanker hybridRanker(RankingConfig rankingConfig) {
        return new HybridRanker(rankingConfig);
    }

    @Bean
    public TopKPolicy topKPolicy(
            @Value("${scenemind.ranking.min-k:3}") int minK,
            @Value("${scenemind.ranking.max-k:12}") int maxK,
            @Value("${scenemind.ranking.min-channels:2}") int minChannels) {
        return new TopKPolicy(minK, maxK, minChannels, 0.08, 0.5);
    }
}
