package com.scenemind.core.eval;

import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.ChannelRanking;
import com.scenemind.core.ranking.RetrievalChannel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可复现的离线评测数据。
 *
 * <p><b>这是一份合成数据集</b>，用于检索策略回归与 A/B 对比，不是线上真实流量。
 * 它刻意构造了三类长视频特有问题：</p>
 * <ol>
 *   <li>相关证据分散在数小时的不同时间窗；</li>
 *   <li>相邻片段重复同一句话（ASR 切片 / OCR 复拍）；</li>
 *   <li>纯画面证据（语音里完全没有，只有 OCR 有）。</li>
 * </ol>
 *
 * <p>固定数据 + 固定随机性，保证任何机器上跑出的指标完全一致。</p>
 */
public final class BenchmarkFixtures {

    public static final long WINDOW_MS = 300_000L;
    public static final int WINDOW_COUNT = 24;   // 2 小时课程
    public static final int TOP_K = 8;

    /** 相关证据所在的时间窗。 */
    private static final Set<Integer> RELEVANT_WINDOWS = Set.of(2, 7, 13, 20);

    /** 其中 13 号窗是「纯画面证据」：语音和词法几乎无信号。 */
    private static final int VISUAL_ONLY_WINDOW = 13;

    public record Scenario(
            String name,
            List<Candidate> candidates,
            Set<String> relevantIds,
            List<String> keywords
    ) {
    }

    private BenchmarkFixtures() {
    }

    public static List<Scenario> scenarios() {
        return List.of(longVideoScenario());
    }

    public static Scenario longVideoScenario() {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> relevant = new LinkedHashSet<>();
        for (int window = 0; window < WINDOW_COUNT; window++) {
            boolean isRelevant = RELEVANT_WINDOWS.contains(window);
            boolean visualOnly = window == VISUAL_ONLY_WINDOW;
            String id = "w" + window;
            String text = isRelevant
                    ? relevantText(window)
                    : fillerText(window);
            double semantic = isRelevant ? (visualOnly ? 0.20 : 0.86) : 0.10 + (window % 7) * 0.03;
            double lexical = isRelevant ? (visualOnly ? 0.10 : 0.90) : 0.05 + (window % 5) * 0.02;
            double visual = isRelevant ? (visualOnly ? 0.95 : 0.82) : 0.04 + (window % 4) * 0.02;
            List<RetrievalChannel> channels = isRelevant
                    ? List.of(RetrievalChannel.SEMANTIC, RetrievalChannel.LEXICAL, RetrievalChannel.VISUAL)
                    : List.of(RetrievalChannel.SEMANTIC);
            candidates.add(new Candidate(id, window * WINDOW_MS,
                    window * WINDOW_MS + 60_000L, semantic, lexical, visual, text, channels));
            if (isRelevant) {
                relevant.add(id);
                // 同窗近重复：内容几乎一致，时间相差 60 秒。
                candidates.add(new Candidate(id + "-dup",
                        window * WINDOW_MS + 60_000L,
                        window * WINDOW_MS + 120_000L,
                        semantic - 0.01, lexical - 0.01, visual - 0.01,
                        text.replace("，", ","), channels));
            }
        }
        return new Scenario("2 小时课程视频 / 跨时段证据检索",
                List.copyOf(candidates), Set.copyOf(relevant),
                List.of("前序遍历", "灰度发布", "构建命令", "上线日期"));
    }

    /** 把候选拆成三条独立的有序召回通道，交给 RRF 融合。 */
    public static List<ChannelRanking> channelRankings(List<Candidate> candidates) {
        return Arrays.stream(RetrievalChannel.values())
                .map(channel -> new ChannelRanking(channel, candidates))
                .toList();
    }

    /**
     * 模拟「远程向量库与本地 cosine 量纲不一致」：对语义分数做正的仿射变换。
     * 单调变换不改变通道内部名次，但会改变线性加权公式里各通道的实际话语权。
     */
    public static List<Candidate> perturbSemantic(List<Candidate> candidates, double scale, double bias) {
        return candidates.stream()
                .map(c -> new Candidate(c.id(), c.startMs(), c.endMs(),
                        scale * c.semanticScore() + bias, c.lexicalScore(), c.visualScore(),
                        c.text(), c.channelsPresent()))
                .toList();
    }

    private static String relevantText(int window) {
        return switch (window) {
            case 2 -> "前序遍历先访问根节点，然后递归访问左子树，最后访问右子树。";
            case 7 -> "会议决定在七月二十五日前完成灰度发布，负责人是张明。";
            case 13 -> "画面提示构建命令 npm run build，输出目录为 dist。";
            case 20 -> "口播说明正式上线日期为八月一日，画面标注 8月3日。";
            default -> fillerText(window);
        };
    }

    private static String fillerText(int window) {
        return "第 " + window + " 段课程过渡内容，介绍本节安排与下一节的预备知识。";
    }
}
