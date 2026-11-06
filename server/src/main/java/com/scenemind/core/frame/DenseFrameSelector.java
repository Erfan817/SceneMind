package com.scenemind.core.frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * dense 抽帧的纯函数选择器。
 *
 * <p>算法与 bilibili-video-summary 的 frames.py 保持一致：</p>
 * <ol>
 *   <li>第一帧永远保留；</li>
 *   <li>后续帧与「上一张保留帧」比较，避免缓慢变化的 PPT 被逐帧稀释；</li>
 *   <li>超过 maxFrames 时按变化像素比例、MAE 从高到低保留，再按时间排序。</li>
 * </ol>
 */
public final class DenseFrameSelector {

    private final FrameChangeDetector detector;

    public DenseFrameSelector() {
        this(new FrameChangeDetector());
    }

    public DenseFrameSelector(FrameChangeDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    public List<FrameSample> select(List<FrameSignature> signatures,
                                    List<Long> timestampsMs,
                                    FrameSelectionConfig config) {
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(timestampsMs, "timestampsMs");
        Objects.requireNonNull(config, "config");
        if (signatures.size() != timestampsMs.size()) {
            throw new IllegalArgumentException("signatures and timestamps must have the same size");
        }
        if (signatures.isEmpty()) return List.of();

        List<FrameSample> kept = new ArrayList<>();
        FrameSignature lastKept = null;
        for (int i = 0; i < signatures.size(); i++) {
            FrameSignature current = signatures.get(i);
            FrameChange change = lastKept == null
                    ? new FrameChange(999.0, 1.0)
                    : detector.compare(lastKept, current);
            if (lastKept == null || change.exceeds(
                    config.denseMaeThreshold(), config.denseChangedRatioThreshold())) {
                kept.add(new FrameSample(i, timestampsMs.get(i), change));
                lastKept = current;
            }
        }

        if (kept.size() <= config.maxFrames()) return List.copyOf(kept);

        return kept.stream()
                .sorted(Comparator
                        .comparingDouble(FrameSample::changedPixelRatio).reversed()
                        .thenComparing(Comparator.comparingDouble(FrameSample::meanAbsoluteError).reversed())
                        .thenComparingLong(FrameSample::timestampMs))
                .limit(config.maxFrames())
                .sorted(Comparator.comparingLong(FrameSample::timestampMs))
                .toList();
    }
}
