package com.scenemind.core.ranking;

import java.util.List;

/**
 * 排序产物 = 结果列表 + 诊断信息。
 */
public record RankingResult(List<RankedItem> items, RankingDiagnostics diagnostics) {
    public RankingResult {
        items = List.copyOf(items);
    }

    public List<Candidate> candidates() {
        return items.stream().map(RankedItem::candidate).toList();
    }
}
