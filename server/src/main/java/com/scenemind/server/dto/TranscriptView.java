package com.scenemind.server.dto;

import java.util.List;

/** 按时间范围切出的转写片段，供外部 Agent 精确读取某几秒的语音/画面文字。 */
public record TranscriptView(
        long startMs,
        long endMs,
        String transcript,
        List<String> ocrTexts
) {
    public TranscriptView {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid transcript range");
        transcript = transcript == null ? "" : transcript.trim();
        ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
    }
}
