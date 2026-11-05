package com.scenemind.core.ranking;

import java.util.List;
import java.util.Objects;

/**
 * 单个通道独立产生的有序候选列表。
 *
 * @param channel 通道类型
 * @param ordered 按该通道原始分降序排列的候选；调用方无需预先排序，内核会重排
 */
public record ChannelRanking(RetrievalChannel channel, List<Candidate> ordered) {
    public ChannelRanking {
        Objects.requireNonNull(channel, "channel");
        ordered = ordered == null ? List.of() : List.copyOf(ordered);
    }
}
