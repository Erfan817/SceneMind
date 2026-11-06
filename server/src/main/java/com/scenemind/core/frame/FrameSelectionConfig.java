package com.scenemind.core.frame;

/**
 * 抽帧策略的全部可调参数。
 *
 * <p>默认值来自 bilibili-video-summary 在课程/PPT 视频上的实测经验：
 * dense 模式每 8 秒采样，MAE 阈值 2.0 足以过滤静止画面；scene 模式用
 * MAE 25 + 变化像素比例 0.55 双指标，避免把人物微表情全部抓进来。</p>
 */
public record FrameSelectionConfig(
        FrameMode mode,
        int denseIntervalSeconds,
        double denseMaeThreshold,
        double denseChangedRatioThreshold,
        double sceneMaeThreshold,
        double sceneChangedRatioThreshold,
        int maxFrames
) {

    public FrameSelectionConfig {
        mode = mode == null ? FrameMode.AUTO : mode;
        if (denseIntervalSeconds <= 0) {
            throw new IllegalArgumentException("denseIntervalSeconds must be positive");
        }
        if (denseMaeThreshold < 0 || denseChangedRatioThreshold < 0) {
            throw new IllegalArgumentException("dense thresholds must be non-negative");
        }
        if (sceneMaeThreshold < 0
                || sceneChangedRatioThreshold < 0
                || sceneChangedRatioThreshold > 1) {
            throw new IllegalArgumentException("scene thresholds are invalid");
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("maxFrames must be positive");
        }
    }

    /** 生产默认：自动选模式，dense 8 秒一采，最多保留 60 帧。 */
    public static FrameSelectionConfig defaults() {
        return new FrameSelectionConfig(FrameMode.AUTO, 8, 2.0, 1.1, 25.0, 0.55, 60);
    }
}
