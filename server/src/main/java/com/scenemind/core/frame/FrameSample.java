package com.scenemind.core.frame;

import java.util.Objects;

/**
 * 一个候选帧及其相对「上一张保留帧」的变化量。
 */
public record FrameSample(int ordinal, long timestampMs, FrameChange change) {

    public FrameSample {
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
        if (timestampMs < 0) throw new IllegalArgumentException("timestampMs must be non-negative");
        change = Objects.requireNonNull(change, "change");
    }

    public double meanAbsoluteError() {
        return change.meanAbsoluteError();
    }

    public double changedPixelRatio() {
        return change.changedPixelRatio();
    }
}
