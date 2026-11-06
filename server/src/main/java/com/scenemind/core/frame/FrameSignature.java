package com.scenemind.core.frame;

import java.util.Objects;

/**
 * 一帧的降采样灰度签名（64x64）。
 *
 * <p>只保留灰度像素，不持有 BufferedImage，因此可以在纯函数层里比较和测试。
 * 构造函数会复制数组，避免外部修改影响已计算的比较结果。</p>
 */
public final class FrameSignature {

    private final double[] pixels;

    public FrameSignature(double[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length == 0) throw new IllegalArgumentException("pixels must not be empty");
        this.pixels = pixels.clone();
    }

    public int size() {
        return pixels.length;
    }

    public double pixelAt(int index) {
        return pixels[index];
    }

    /** 包内读取，避免每次比较都复制数组。 */
    double[] pixelsForRead() {
        return pixels;
    }
}
