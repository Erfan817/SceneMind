package com.scenemind.core.frame;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseFrameSelectorTest {

    private final DenseFrameSelector selector = new DenseFrameSelector();

    @Test
    void keepsFirstFrameAndSuppressesTinyChanges() {
        List<FrameSignature> frames = List.of(
                frame(0), frame(1), frame(3));
        List<Long> timestamps = List.of(0L, 8_000L, 16_000L);

        List<FrameSample> kept = selector.select(frames, timestamps, config(10));

        assertEquals(List.of(0, 2), kept.stream().map(FrameSample::ordinal).toList());
        assertEquals(3.0, kept.get(1).meanAbsoluteError());
    }

    @Test
    void comparesAgainstLastKeptFrameSoGradualSlidesAccumulate() {
        // 每 8 秒只变 1，单帧看低于阈值；但相对上一张保留帧累计到 2 后必须保留。
        List<FrameSignature> frames = List.of(
                frame(0), frame(1), frame(2), frame(3), frame(4));
        List<Long> timestamps = List.of(0L, 8_000L, 16_000L, 24_000L, 32_000L);

        List<FrameSample> kept = selector.select(frames, timestamps, config(10));

        assertEquals(List.of(0, 2, 4), kept.stream().map(FrameSample::ordinal).toList());
    }

    @Test
    void capsByChangeRatioThenRestoresChronologicalOrder() {
        List<FrameSignature> frames = List.of(
                frame(0), frame(30), frame(60), frame(75));
        List<Long> timestamps = List.of(0L, 8_000L, 16_000L, 24_000L);

        List<FrameSample> kept = selector.select(frames, timestamps, config(2));

        // 第一帧按约定始终保留（bvs 同样把首帧视为高价值候选），
        // 剩余名额给变化像素比例最高的第二帧。
        assertEquals(List.of(0, 1), kept.stream().map(FrameSample::ordinal).toList());
        assertTrue(kept.get(0).timestampMs() < kept.get(1).timestampMs());
    }

    @Test
    void emptyInputProducesEmptySelection() {
        assertTrue(selector.select(List.of(), List.of(), config(10)).isEmpty());
    }

    private FrameSelectionConfig config(int maxFrames) {
        return new FrameSelectionConfig(FrameMode.DENSE, 8, 2.0, 1.1, 25.0, 0.55, maxFrames);
    }

    private FrameSignature frame(double value) {
        double[] pixels = new double[16];
        Arrays.fill(pixels, value);
        return new FrameSignature(pixels);
    }
}
