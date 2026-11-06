package com.scenemind.server.dto;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 */
public record VideoContext(
        String source,
        String userGoal,
        List<VideoSegment> segments
) {
    public VideoContext {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("video source is required");
        userGoal = userGoal == null ? "" : userGoal.trim();
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * 一个 60s 时间窗内的多模态片段。
     *
     * <p>{@code visualCaptions} 是关键帧的 VLM 描述，只参与检索召回，
     * 不能作为 {@code EvidenceAuditor} 的逐字证据；可引用来源仍是 transcript / ocrTexts。</p>
     */
    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames,
            List<String> visualCaptions
    ) {
        public VideoSegment {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
            transcript = transcript == null ? "" : transcript.trim();
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
            evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
            visualCaptions = visualCaptions == null ? List.of() : List.copyOf(visualCaptions);
        }

        /** 无画面描述时的便捷构造，保持既有调用点与测试兼容。 */
        public VideoSegment(long startMs, long endMs, String transcript,
                            List<String> ocrTexts, List<String> evidenceFrames) {
            this(startMs, endMs, transcript, ocrTexts, evidenceFrames, List.of());
        }
    }

    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
