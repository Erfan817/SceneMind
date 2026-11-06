package com.scenemind.core.frame;

import java.util.Locale;

/**
 * 抽帧模式。
 *
 * <p>{@link #SCENE} 沿用 FFmpeg 场景检测，适合对谈、访谈、播客等画面变化不频繁的视频；
 * {@link #DENSE} 按固定间隔采样，适合 PPT、板书、代码演示等「画面变化很小但信息逐行出现」
 * 的课程类视频；{@link #AUTO} 由标题/目标里的内容线索选择。</p>
 */
public enum FrameMode {

    AUTO,
    SCENE,
    DENSE;

    public static FrameMode fromNullable(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的抽帧模式: " + value, e);
        }
    }
}
