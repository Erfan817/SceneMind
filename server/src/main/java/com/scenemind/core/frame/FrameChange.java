package com.scenemind.core.frame;

/**
 * 两帧灰度签名之间的变化量。
 *
 * @param meanAbsoluteError 64x64 灰度图的平均绝对误差（0–255）
 * @param changedPixelRatio 灰度差超过 20 的像素占比（0–1）
 */
public record FrameChange(double meanAbsoluteError, double changedPixelRatio) {

    public FrameChange {
        if (meanAbsoluteError < 0) {
            throw new IllegalArgumentException("meanAbsoluteError must be non-negative");
        }
        if (changedPixelRatio < 0 || changedPixelRatio > 1) {
            throw new IllegalArgumentException("changedPixelRatio must be within [0,1]");
        }
    }

    public boolean exceeds(double maeThreshold, double ratioThreshold) {
        return meanAbsoluteError >= maeThreshold || changedPixelRatio >= ratioThreshold;
    }
}
