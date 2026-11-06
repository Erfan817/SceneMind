package com.scenemind.server.dto;

import java.util.List;

/**
 * 某一时刻的画面证据视图：给外部 Agent（或调试面板）一个可以"亲眼核验"的入口。
 *
 * <p>{@code frameUrl} 来自平台 MinIO 上已落库的关键帧（或本地路径回退）；返回 URL 而不返回
 * 图片二进制，是为了复用既有鉴权链路并避免 MCP 传输大块图像。</p>
 */
public record FrameView(
        long timestampMs,
        long startMs,
        long endMs,
        String frameUrl,
        List<String> ocrTexts,
        String transcript,
        String source
) {
    public FrameView {
        if (timestampMs < 0 || startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("invalid frame range");
        }
        frameUrl = frameUrl == null ? "" : frameUrl.trim();
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        transcript = transcript == null ? "" : transcript.trim();
        source = source == null ? "" : source.trim();
    }
}
