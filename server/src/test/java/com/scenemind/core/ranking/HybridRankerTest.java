package com.scenemind.core.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRankerTest {

    private final HybridRanker ranker = new HybridRanker();

    private static Candidate candidate(String id, long startMs, double semantic,
                                       double lexical, double visual, String text) {
        return new Candidate(id, startMs, startMs + 60_000L,
                semantic, lexical, visual, text,
                List.of(RetrievalChannel.SEMANTIC, RetrievalChannel.LEXICAL, RetrievalChannel.VISUAL));
    }

    private static List<ChannelRanking> channels(List<Candidate> candidates) {
        return Arrays.stream(RetrievalChannel.values())
                .map(channel -> new ChannelRanking(channel, candidates))
                .toList();
    }

    private static List<String> ids(List<RankedItem> items) {
        return items.stream().map(item -> item.candidate().id()).toList();
    }

    @Test
    void rrfIsInvariantToMonotonicScoreRescaling() {
        List<Candidate> base = List.of(
                candidate("a", 0, 0.9, 0.8, 0.7, "前序遍历的递归顺序"),
                candidate("b", 300_000, 0.5, 0.4, 0.3, "灰度发布的负责人"),
                candidate("c", 600_000, 0.2, 0.1, 0.9, "构建命令与输出目录"));
        List<Candidate> rescaled = base.stream()
                .map(c -> new Candidate(c.id(), c.startMs(), c.endMs(),
                        37 * c.semanticScore() + 5, c.lexicalScore(), c.visualScore(),
                        c.text(), c.channelsPresent()))
                .toList();
        assertEquals(ids(ranker.fuse(channels(base)).items()),
                ids(ranker.fuse(channels(rescaled)).items()),
                "单调变换不改变通道内名次，RRF 结果必须完全一致");
    }

    @Test
    void suppressesNearDuplicateSegmentsInsideTheSameWindow() {
        String text = "前序遍历先访问根节点，然后递归访问左子树，最后访问右子树。";
        List<Candidate> candidates = List.of(
                candidate("main", 0, 0.9, 0.9, 0.9, text),
                candidate("dup", 60_000, 0.89, 0.89, 0.89, text.replace("，", ",")),
                candidate("other", 300_000, 0.4, 0.4, 0.4, "灰度发布的截止时间"));
        RankingResult result = ranker.rank(channels(candidates));
        assertTrue(result.diagnostics().duplicatesSuppressed() >= 1);
        assertFalse(result.candidates().stream().anyMatch(c -> c.id().equals("dup")),
                "近重复副本不应占据最终名额");
    }

    @Test
    void spreadsResultsAcrossDistinctTimeWindows() {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(candidate("same-1", 0, 0.95, 0.9, 0.9, "第一段独立内容"));
        candidates.add(candidate("same-2", 60_000, 0.94, 0.9, 0.9, "第二段独立内容"));
        candidates.add(candidate("same-3", 120_000, 0.93, 0.9, 0.9, "第三段独立内容"));
        candidates.add(candidate("w1", 300_000, 0.6, 0.6, 0.6, "第四段独立内容"));
        candidates.add(candidate("w2", 600_000, 0.5, 0.5, 0.5, "第五段独立内容"));
        candidates.add(candidate("w3", 900_000, 0.4, 0.4, 0.4, "第六段独立内容"));
        RankingResult result = ranker.select(ranker.fuse(channels(candidates)), 3);
        assertEquals(3, result.items().size());
        assertEquals(3, result.diagnostics().windowsCovered(),
                "预算为 3 时应尽量覆盖 3 个不同时间窗，而不是全挤在同一段");
    }

    @Test
    void emptyChannelsProduceEmptyResultWithExplanation() {
        RankingResult result = ranker.rank(List.of());
        assertTrue(result.items().isEmpty());
        assertTrue(result.diagnostics().notes().stream()
                .anyMatch(note -> note.contains("无召回通道")));
    }

    @Test
    void zeroWeightChannelIsSkipped() {
        EnumMap<RetrievalChannel, Double> weights = new EnumMap<>(RetrievalChannel.class);
        weights.put(RetrievalChannel.SEMANTIC, 0.0);
        weights.put(RetrievalChannel.LEXICAL, 1.0);
        weights.put(RetrievalChannel.VISUAL, 1.0);
        RankingConfig config = new RankingConfig(60, weights, 300_000L, 0.82, 8);
        HybridRanker custom = new HybridRanker(config);
        List<Candidate> candidates = List.of(
                candidate("a", 0, 1.0, 0.1, 0.1, "语义很高但词法视觉都很低"),
                candidate("b", 300_000, 0.0, 0.9, 0.9, "词法视觉都很高"));
        List<RankedItem> fused = custom.fuse(channels(candidates)).items();
        assertEquals("b", fused.get(0).candidate().id());
        assertTrue(fused.get(0).channelRanks().size() <= 2);
        assertTrue(custom.fuse(channels(candidates)).notes().stream()
                .anyMatch(note -> note.contains("权重为 0")));
    }

    @Test
    void selectionHonorsExternalBudget() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(candidate("c" + i, i * 300_000L,
                    0.9 - i * 0.05, 0.5, 0.5, "独立片段 " + i));
        }
        RankingResult result = ranker.select(ranker.fuse(channels(candidates)), 2);
        assertEquals(2, result.items().size());
    }

    @Test
    void channelIsolationKeepsSingleChannelCandidatesFromBorrowingOtherRanks() {
        List<Candidate> candidates = List.of(
                new Candidate("asr-only", 0, 60_000L, 0.9, 0.8, 0.0,
                        "只有语音", List.of(RetrievalChannel.SEMANTIC, RetrievalChannel.LEXICAL)),
                new Candidate("full", 300_000L, 360_000L, 0.8, 0.8, 0.9,
                        "语音与画面", List.of(RetrievalChannel.SEMANTIC,
                                RetrievalChannel.LEXICAL, RetrievalChannel.VISUAL)));
        List<RankedItem> fused = ranker.fuse(channels(candidates)).items();
        Map<String, RankedItem> byId = new java.util.LinkedHashMap<>();
        fused.forEach(item -> byId.put(item.candidate().id(), item));

        assertEquals(2, byId.get("asr-only").matchedChannels().size(),
                "没有 OCR 的片段不能从视觉通道借名次");
        assertFalse(byId.get("asr-only").channelRanks().containsKey(RetrievalChannel.VISUAL));
        assertEquals(3, byId.get("full").matchedChannels().size());
    }

    @Test
    void weakSingleChannelEvidenceCanTriggerTopKExpansion() {
        // 纯 ASR 视频：所有候选都只有语义通道，没有词法命中与视觉证据。
        // 修复前每个候选恒为 3 通道，TopKPolicy 的 EXPAND 分支线上永远不可达。
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            candidates.add(new Candidate("c" + i, i * 300_000L, i * 300_000L + 60_000L,
                    0.9 - i * 0.05, 0.4, 0.0, "语音片段 " + i,
                    List.of(RetrievalChannel.SEMANTIC)));
        }
        List<RankedItem> fused = ranker.fuse(channels(candidates)).items();
        assertEquals(1, fused.get(0).matchedChannels().size());
        TopKPolicy.Decision decision = new TopKPolicy().decide(fused, 5);
        assertEquals(TopKPolicy.Action.EXPAND, decision.action(),
                "头部证据偏弱时应能扩召回");
    }

    @Test
    void unannotatedLegacyCandidatesKeepTheOldBehavior() {
        List<Candidate> legacy = List.of(
                new Candidate("legacy", 0, 60_000L, 0.9, 0.8, 0.7, "旧数据", List.of()));
        List<RankedItem> fused = ranker.fuse(channels(legacy)).items();
        assertEquals(3, fused.get(0).matchedChannels().size(),
                "未标注通道的历史数据不应被过滤掉");
    }

    @Test
    void invalidConfigIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RankingConfig(0, Map.of(), 300_000L, 0.8, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new RankingConfig(60, Map.of(), 0L, 0.8, 8));
    }
}
