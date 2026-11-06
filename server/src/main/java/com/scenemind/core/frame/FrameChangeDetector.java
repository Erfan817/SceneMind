package com.scenemind.core.frame;

import java.util.Objects;

/**
 * 用 MAE + 变化像素比例衡量两帧差异。
 *
 * <p>只用 MAE 会把人物微表情、口型变化都算成变化；加上「变化像素比例」
 * 后，只有真正发生大面积内容切换的画面才会被保留。该实现与
 * bilibili-video-summary 的 64x64 灰度签名实测口径一致。</p>
 */
public final class FrameChangeDetector {

    private static final double PIXEL_CHANGE_THRESHOLD = 20.0;

    public FrameChange compare(FrameSignature previous, FrameSignature current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (previous.size() != current.size()) {
            throw new IllegalArgumentException("frame signatures must have the same size");
        }

        double[] before = previous.pixelsForRead();
        double[] after = current.pixelsForRead();
        double sum = 0;
        int changed = 0;
        for (int i = 0; i < before.length; i++) {
            double diff = Math.abs(before[i] - after[i]);
            sum += diff;
            if (diff > PIXEL_CHANGE_THRESHOLD) changed++;
        }
        return new FrameChange(sum / before.length, (double) changed / before.length);
    }
}
