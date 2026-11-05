package com.scenemind.core.ranking;

import java.util.List;
import java.util.Objects;

/**
 * 一个可被检索的视频证据片段。
 *
 * <p>这是检索内核与外部世界之间唯一的数据契约：上层（Spring 服务）负责把
 * {@code VideoChunk} / {@code VideoSegment} 映射成 Candidate，内核只做排序，
 * 不感知数据库、向量库或模型。这样检索策略可以脱离中间件离线回放。</p>
 *
 * @param id              稳定标识，同一视频内唯一，通常用 "startMs:endMs"
 * @param startMs         片段起始毫秒（含）
 * @param endMs           片段结束毫秒（不含）
 * @param semanticScore   语义通道原始分，仅用于生成该通道排名
 * @param lexicalScore    词法通道原始分
 * @param visualScore     视觉通道原始分
 * @param text            可展示文本，参与冗余判定
 * @param channelsPresent 该候选实际拥有的证据通道，用于诊断与通道一致性校验
 */
public record Candidate(
        String id,
        long startMs,
        long endMs,
        double semanticScore,
        double lexicalScore,
        double visualScore,
        String text,
        List<RetrievalChannel> channelsPresent
) {
    public Candidate {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid time range");
        text = text == null ? "" : text;
        channelsPresent = channelsPresent == null ? List.of() : List.copyOf(channelsPresent);
    }

    public double scoreOf(RetrievalChannel channel) {
        return switch (channel) {
            case SEMANTIC -> semanticScore;
            case LEXICAL -> lexicalScore;
            case VISUAL -> visualScore;
        };
    }

    public long midMs() {
        return startMs + (endMs - startMs) / 2;
    }

    public long windowId(long windowMs) {
        return midMs() / windowMs;
    }
}
