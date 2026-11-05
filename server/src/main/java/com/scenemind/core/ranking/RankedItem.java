package com.scenemind.core.ranking;

import java.util.List;
import java.util.Map;

/**
 * 融合排序的单条结果，保留「为什么排在这里」的全部依据。
 *
 * @param candidate       原始候选
 * @param fusedScore      RRF 融合分（无量纲）
 * @param channelRanks    各通道中的 1-based 排名；未出现在该通道则为 0
 * @param matchedChannels 实际命中的通道
 * @param selectionPass   被选中的阶段：1 = 覆盖优先轮，2 = 补足轮
 */
public record RankedItem(
        Candidate candidate,
        double fusedScore,
        Map<RetrievalChannel, Integer> channelRanks,
        List<RetrievalChannel> matchedChannels,
        int selectionPass
) {
    public RankedItem {
        channelRanks = Map.copyOf(channelRanks);
        matchedChannels = List.copyOf(matchedChannels);
    }
}
