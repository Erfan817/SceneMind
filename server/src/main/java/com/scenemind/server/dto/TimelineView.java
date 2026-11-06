package com.scenemind.server.dto;

import java.util.List;

/**
 * 视频时间轴视图：给外部 Agent 一张「先看哪里」的地图。
 *
 * <p>优先使用 5 分钟 chunk 摘要作为章节；没有 chunk 时按 5 分钟窗口从
 * {@link VideoContext} 聚合。它只做导航与定位，不替代逐条证据核验。</p>
 */
public record TimelineView(
        long mediaId,
        long durationMs,
        List<Entry> entries
) {
    public TimelineView {
        if (mediaId < 0) throw new IllegalArgumentException("mediaId cannot be negative");
        if (durationMs < 0) throw new IllegalArgumentException("durationMs cannot be negative");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Entry(
            long startMs,
            long endMs,
            String summary,
            List<String> keywords,
            int segmentCount,
            int ocrSegmentCount,
            int captionSegmentCount,
            String frameUrl
    ) {
        public Entry {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid timeline range");
            summary = summary == null ? "" : summary.trim();
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            frameUrl = frameUrl == null ? "" : frameUrl.trim();
        }
    }
}
