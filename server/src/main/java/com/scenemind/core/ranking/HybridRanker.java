package com.scenemind.core.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 多通道证据融合排序内核（纯函数、零框架依赖）。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>RRF 融合</b>：每个通道独立产出有序列表，最终得分只由「名次」决定
 *       {@code score(d) = Σ w_c / (k + rank_c(d))}。这样不同通道的分数无需归一化，
 *       远程向量库分数与本地 cosine 也不会互相污染。</li>
 *   <li><b>时间窗覆盖</b>：第一轮每个时间窗只取最优的一条，避免数小时视频的
 *       TopK 全部挤在同一段；不足名额时第二轮再按融合分补满。</li>
 *   <li><b>近重复抑制</b>：时间相邻且文本 bigram 相似度超阈值的片段只保留高分者。</li>
 *   <li><b>可解释</b>：返回每条结果的通道名次、选中阶段与全局诊断，方便排障与评测。</li>
 * </ol>
 *
 * <p>融合（{@link #fuse}）与选择（{@link #select}）刻意拆成两步：
 * 上层可以先拿到全量融合排序，用 {@link TopKPolicy} 动态决定预算，再执行选择。
 * 该类不持有状态、不访问网络或数据库，可直接用于单元测试、离线回放与 A/B 评测。</p>
 */
public final class HybridRanker {

    /** 融合阶段的产物：全量排序 + 决策线索。 */
    public record Fusion(
            List<RankedItem> items,
            List<RetrievalChannel> channelsUsed,
            List<String> notes
    ) {
        public Fusion {
            items = List.copyOf(items);
            channelsUsed = List.copyOf(channelsUsed);
            notes = List.copyOf(notes);
        }
    }

    private final RankingConfig config;

    public HybridRanker() {
        this(RankingConfig.defaults());
    }

    public HybridRanker(RankingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public RankingConfig config() {
        return config;
    }

    /** 一步到位的默认入口。 */
    public RankingResult rank(List<ChannelRanking> channelRankings) {
        return select(fuse(channelRankings), config.maxResults());
    }

    /** 第一步：多通道 RRF 融合，返回全量、按融合分降序的结果。 */
    public Fusion fuse(List<ChannelRanking> channelRankings) {
        Objects.requireNonNull(channelRankings, "channelRankings");
        List<String> notes = new ArrayList<>();

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<String, EnumMap<RetrievalChannel, Integer>> ranksById = new LinkedHashMap<>();
        Set<RetrievalChannel> usedChannels = new LinkedHashSet<>();

        for (ChannelRanking channelRanking : channelRankings) {
            if (channelRanking == null || channelRanking.ordered().isEmpty()) continue;
            RetrievalChannel channel = channelRanking.channel();
            usedChannels.add(channel);
            if (config.weightOf(channel) <= 0) {
                notes.add("通道 " + channel.displayName() + " 权重为 0，已跳过");
                continue;
            }

            List<Candidate> ordered = new ArrayList<>(channelRanking.ordered());
            ordered.sort(Comparator.comparingDouble((Candidate c) -> c.scoreOf(channel)).reversed()
                    .thenComparing(Candidate::startMs)
                    .thenComparing(Candidate::id));

            Set<String> seenInChannel = new HashSet<>();
            int rank = 0;
            for (Candidate candidate : ordered) {
                if (!seenInChannel.add(candidate.id())) continue;
                rank++;
                candidates.putIfAbsent(candidate.id(), candidate);
                ranksById.computeIfAbsent(candidate.id(), ignored ->
                        new EnumMap<>(RetrievalChannel.class)).put(channel, rank);
            }
        }

        if (channelRankings.isEmpty()) notes.add("无召回通道，返回空结果");
        if (candidates.isEmpty() && !channelRankings.isEmpty()) notes.add("所有通道均无候选");

        List<RankedItem> fused = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            EnumMap<RetrievalChannel, Integer> ranks =
                    ranksById.getOrDefault(candidate.id(), new EnumMap<>(RetrievalChannel.class));
            double fusedScore = 0.0;
            List<RetrievalChannel> matched = new ArrayList<>();
            for (Map.Entry<RetrievalChannel, Integer> entry : ranks.entrySet()) {
                double weight = config.weightOf(entry.getKey());
                if (weight <= 0) continue;
                fusedScore += weight / (config.rrfK() + entry.getValue());
                matched.add(entry.getKey());
            }
            fused.add(new RankedItem(candidate, fusedScore, ranks, matched, 0));
        }
        fused.sort(Comparator.comparingDouble(RankedItem::fusedScore).reversed()
                .thenComparing((RankedItem item) -> item.matchedChannels().size(), Comparator.reverseOrder())
                .thenComparing(item -> item.candidate().startMs())
                .thenComparing(item -> item.candidate().id()));

        return new Fusion(fused, List.copyOf(usedChannels), notes);
    }

    /** 第二步：按给定预算做「时间窗覆盖 + 近重复抑制 + 补足」。 */
    public RankingResult select(Fusion fusion, int maxResults) {
        Objects.requireNonNull(fusion, "fusion");
        int budget = Math.max(1, maxResults);
        List<String> notes = new ArrayList<>(fusion.notes());
        List<RankedItem> fused = fusion.items();

        List<RankedItem> selected = new ArrayList<>();
        Set<Long> usedWindows = new HashSet<>();
        Set<String> pickedIds = new HashSet<>();
        int suppressed = 0;

        for (RankedItem item : fused) {
            if (selected.size() >= budget) break;
            if (isRedundant(item.candidate(), selected)) {
                suppressed++;
                continue;
            }
            long window = item.candidate().windowId(config.windowMs());
            if (!usedWindows.add(window)) continue;
            selected.add(withPass(item, 1));
            pickedIds.add(item.candidate().id());
        }

        int secondPass = 0;
        for (RankedItem item : fused) {
            if (selected.size() >= budget) break;
            if (pickedIds.contains(item.candidate().id())) continue;
            if (isRedundant(item.candidate(), selected)) {
                suppressed++;
                continue;
            }
            selected.add(withPass(item, 2));
            pickedIds.add(item.candidate().id());
            secondPass++;
        }

        selected.sort(Comparator.comparingDouble(RankedItem::fusedScore).reversed()
                .thenComparing(item -> item.candidate().startMs()));

        int windowsCovered = (int) selected.stream()
                .map(item -> item.candidate().windowId(config.windowMs()))
                .distinct()
                .count();
        if (suppressed > 0) notes.add("抑制近重复片段 " + suppressed + " 条");
        if (secondPass > 0) notes.add("第二轮补足 " + secondPass + " 条");
        notes.add("覆盖 " + windowsCovered + " 个时间窗，预算 " + budget
                + "，每窗宽度 " + config.windowMs() + " ms");

        RankingDiagnostics diagnostics = new RankingDiagnostics(
                fused.size(), suppressed, windowsCovered, secondPass,
                fusion.channelsUsed(), notes);
        return new RankingResult(selected, diagnostics);
    }

    private boolean isRedundant(Candidate candidate, List<RankedItem> selected) {
        for (RankedItem item : selected) {
            Candidate other = item.candidate();
            if (Math.abs(candidate.midMs() - other.midMs()) >= config.windowMs()) continue;
            if (TextSimilarity.jaccard(candidate.text(), other.text())
                    >= config.redundancySimilarity()) {
                return true;
            }
        }
        return false;
    }

    private static RankedItem withPass(RankedItem item, int pass) {
        return new RankedItem(item.candidate(), item.fusedScore(),
                item.channelRanks(), item.matchedChannels(), pass);
    }
}
