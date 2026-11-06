package com.scenemind.server.dto;

import java.util.List;

/**
 * 用户可直接跳转和核验的视频证据。
 *
 * <p>{@code visualCaptions} 是 VLM 生成的画面描述，只作为检索线索返回，
 * 不是可引用证据；{@code source} 仅在无 ASR/OCR 时标为 {@code VISION}。</p>
 */
public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String source,
        String snippet,
        String transcript,
        List<String> ocrTexts,
        List<String> visualCaptions
) {
    public VideoEvidenceHit {
        source = source == null ? "" : source;
        snippet = snippet == null ? "" : snippet;
        transcript = transcript == null ? "" : transcript;
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        visualCaptions = visualCaptions == null ? List.of() : List.copyOf(visualCaptions);
    }

    /** 无画面描述时的便捷构造。 */
    public VideoEvidenceHit(long startMs, long endMs, String source, String snippet,
                            String transcript, List<String> ocrTexts) {
        this(startMs, endMs, source, snippet, transcript, ocrTexts, List.of());
    }
}
