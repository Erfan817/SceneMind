package com.scenemind.core.frame;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameChangeDetectorTest {

    private final FrameChangeDetector detector = new FrameChangeDetector();

    @Test
    void identicalFramesHaveNoChange() {
        double[] pixels = new double[16];
        Arrays.fill(pixels, 100);

        FrameChange change = detector.compare(new FrameSignature(pixels), new FrameSignature(pixels));

        assertEquals(0.0, change.meanAbsoluteError());
        assertEquals(0.0, change.changedPixelRatio());
    }

    @Test
    void smallGlobalShiftDoesNotCountAsChangedPixels() {
        double[] before = new double[16];
        double[] after = new double[16];
        Arrays.fill(before, 100);
        Arrays.fill(after, 110);

        FrameChange change = detector.compare(new FrameSignature(before), new FrameSignature(after));

        assertEquals(10.0, change.meanAbsoluteError());
        assertEquals(0.0, change.changedPixelRatio());
    }

    @Test
    void largeChangeCountsEveryPixel() {
        double[] before = new double[16];
        double[] after = new double[16];
        Arrays.fill(before, 100);
        Arrays.fill(after, 140);

        FrameChange change = detector.compare(new FrameSignature(before), new FrameSignature(after));

        assertEquals(40.0, change.meanAbsoluteError());
        assertEquals(1.0, change.changedPixelRatio());
    }

    @Test
    void rejectsSignaturesOfDifferentSizes() {
        assertThrows(IllegalArgumentException.class, () -> detector.compare(
                new FrameSignature(new double[16]),
                new FrameSignature(new double[25])));
    }
}
