package com.scenemind.core.eval;

import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.RankedItem;
import com.scenemind.core.ranking.TextSimilarity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线评测指标。全部是确定性纯函数，不含任何随机性或外部调用。
 */
public final class EvaluationMetrics {

    /** 一次排序策略的四个核心指标。 */
    public record Metrics(
            double recallAtK,
            double temporalCoverage,
            double redundancyRate,
            double rankStability
    ) {
        public String toRow(String label) {
            return String.format(java.util.Locale.ROOT,
                    "| %s | %.3f | %.3f | %.3f | %.3f |",
                    label, recallAtK, temporalCoverage, redundancyRate, rankStability);
        }
    }

    private EvaluationMetrics() {
    }

    /**
     * TopK 中命中相关证据的比例。
     * 近重复副本不算新召回 —— 它挤占的是本该留给其他时段证据的位置。
     */
    public static double recallAtK(List<Candidate> top, Set<String> relevantIds) {
        if (relevantIds.isEmpty()) return 1.0;
        long hit = top.stream().filter(c -> relevantIds.contains(c.id())).count();
        return (double) hit / relevantIds.size();
    }

    /** 命中了多少个不同的相关时间窗（长视频最关心的覆盖能力）。 */
    public static double temporalCoverage(List<Candidate> top, Set<String> relevantIds, long windowMs) {
        if (relevantIds.isEmpty()) return 1.0;
        Set<Long> relevantWindows = new HashSet<>();
        for (String id : relevantIds) {
            int window = windowOf(id);
            if (window >= 0) relevantWindows.add((window * windowMs + 30_000L) / windowMs);
        }
        Set<Long> hitWindows = new HashSet<>();
        for (Candidate candidate : top) {
            if (!relevantIds.contains(candidate.id())) continue;
            hitWindows.add(candidate.windowId(windowMs));
        }
        if (relevantWindows.isEmpty()) return 0.0;
        return (double) hitWindows.size() / relevantWindows.size();
    }

    /** TopK 内部互为「时间相邻 + 文本近重复」的配对占比。 */
    public static double redundancyRate(List<Candidate> top, long windowMs, double similarityThreshold) {
        if (top.size() < 2) return 0.0;
        int pairs = 0;
        int redundant = 0;
        for (int i = 0; i < top.size(); i++) {
            for (int j = i + 1; j < top.size(); j++) {
                pairs++;
                Candidate a = top.get(i);
                Candidate b = top.get(j);
                if (Math.abs(a.midMs() - b.midMs()) < windowMs
                        && TextSimilarity.jaccard(a.text(), b.text()) >= similarityThreshold) {
                    redundant++;
                }
            }
        }
        return pairs == 0 ? 0.0 : (double) redundant / pairs;
    }

    /** 两次结果 TopK 集合的 Jaccard，用来量化「分数尺度变化后排序是否漂移」。 */
    public static double rankStability(Set<String> before, Set<String> after) {
        if (before.isEmpty() && after.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(before);
        intersection.retainAll(after);
        Set<String> union = new HashSet<>(before);
        union.addAll(after);
        return union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
    }

    public static Set<String> idsOf(List<Candidate> candidates) {
        Set<String> ids = new HashSet<>();
        candidates.forEach(c -> ids.add(normalizeId(c.id())));
        return ids;
    }

    public static Set<String> idsOfItems(List<RankedItem> items) {
        Set<String> ids = new HashSet<>();
        items.forEach(item -> ids.add(normalizeId(item.candidate().id())));
        return ids;
    }

    /** 近重复副本统一折算到主片段 id，避免把重复项算成「新召回」。 */
    private static String normalizeId(String id) {
        return id.endsWith("-dup") ? id.substring(0, id.length() - 4) : id;
    }

    private static int windowOf(String id) {
        if (!id.startsWith("w")) return -1;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
