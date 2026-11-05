package com.scenemind.core.ranking;

import java.util.List;
import java.util.Locale;

/**
 * 自适应召回预算。
 *
 * <p>固定 {@code TOP_K = 3} 在长视频上会两头不讨好：证据充足时把不相干片段硬塞给模型，
 * 证据稀疏时又递归不到足够上下文。这里按「融合分分布 + 通道命中数」动态调整截断位置，
 * 并把决策理由一并返回，让每次检索都能解释。</p>
 *
 * <ul>
 *   <li>头部只命中单一通道 → 证据偏弱，扩大召回；</li>
 *   <li>截断处出现分数平台 → 说明同质量片段被切开，扩一位避免丢证据；</li>
 *   <li>头部三通道齐中且分数显著领先 → 收缩，省 Token、降延迟。</li>
 * </ul>
 */
public final class TopKPolicy {

    public enum Action { KEEP, EXPAND, SHRINK }

    public record Decision(int topK, Action action, String reason) {
    }

    private final int minK;
    private final int maxK;
    private final int minChannels;
    private final double plateauRatio;
    private final double dominanceGap;

    public TopKPolicy() {
        this(3, 12, 2, 0.08, 0.5);
    }

    public TopKPolicy(int minK, int maxK, int minChannels,
                      double plateauRatio, double dominanceGap) {
        if (minK <= 0 || maxK < minK) throw new IllegalArgumentException("invalid k range");
        this.minK = minK;
        this.maxK = maxK;
        this.minChannels = minChannels;
        this.plateauRatio = plateauRatio;
        this.dominanceGap = dominanceGap;
    }

    public Decision decide(List<RankedItem> fused, int requestedK) {
        int k = Math.max(minK, Math.min(maxK, requestedK));
        if (fused == null || fused.isEmpty()) {
            return new Decision(minK, Action.KEEP, "无可用候选，保持最小预算");
        }
        RankedItem top = fused.get(0);

        if (top.matchedChannels().size() < minChannels && fused.size() > k) {
            int expanded = Math.min(maxK, Math.max(k + 2, Math.min(fused.size(), k + 2)));
            return new Decision(expanded, Action.EXPAND,
                    String.format(Locale.ROOT,
                            "头部仅命中 %d 个通道，证据偏弱，预算 %d -> %d",
                            top.matchedChannels().size(), k, expanded));
        }

        if (k < fused.size()) {
            double atBoundary = fused.get(k - 1).fusedScore();
            double next = fused.get(k).fusedScore();
            if (atBoundary > 0 && (atBoundary - next) / atBoundary < plateauRatio) {
                int expanded = Math.min(maxK, k + 1);
                if (expanded > k) {
                    return new Decision(expanded, Action.EXPAND,
                            String.format(Locale.ROOT,
                                    "截断处出现分数平台（%.4f / %.4f），预算 %d -> %d",
                                    atBoundary, next, k, expanded));
                }
            }
        }

        if (fused.size() >= 2 && top.matchedChannels().size() >= 3) {
            double second = fused.get(1).fusedScore();
            double gap = top.fusedScore() > 0
                    ? (top.fusedScore() - second) / top.fusedScore() : 0;
            if (gap >= dominanceGap) {
                int shrunk = Math.max(minK, k - 2);
                if (shrunk < k) {
                    return new Decision(shrunk, Action.SHRINK,
                            String.format(Locale.ROOT,
                                    "头部三通道齐中且领先 %.0f%%，预算 %d -> %d",
                                    gap * 100, k, shrunk));
                }
            }
        }
        return new Decision(k, Action.KEEP, "证据分布正常，维持固定预算");
    }
}
