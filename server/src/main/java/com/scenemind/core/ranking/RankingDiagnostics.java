package com.scenemind.core.ranking;

import java.util.List;

/**
 * 一次检索的可解释诊断，供日志、评测与前端调试面板消费。
 *
 * @param candidatesSeen        参与融合的去重后候选总数
 * @param duplicatesSuppressed  因「时间相邻 + 文本高度相似」被抑制的条数
 * @param windowsCovered        结果覆盖的不同时间窗数量
 * @param secondPassFills       第二轮（放宽每窗一条）补入的条数
 * @param channelsUsed          本次实际参与融合的通道
 * @param notes                 人类可读的决策说明，便于线上排障
 */
public record RankingDiagnostics(
        int candidatesSeen,
        int duplicatesSuppressed,
        int windowsCovered,
        int secondPassFills,
        List<RetrievalChannel> channelsUsed,
        List<String> notes
) {
    public RankingDiagnostics {
        channelsUsed = List.copyOf(channelsUsed);
        notes = List.copyOf(notes);
    }
}
