package com.scenemind.core.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopKPolicyTest {

    private final TopKPolicy policy = new TopKPolicy();

    private static RankedItem item(String id, double score, int channels, int rank) {
        Map<RetrievalChannel, Integer> ranks = Map.of(RetrievalChannel.SEMANTIC, rank);
        List<RetrievalChannel> matched = channels >= 3
                ? List.of(RetrievalChannel.SEMANTIC, RetrievalChannel.LEXICAL, RetrievalChannel.VISUAL)
                : List.of(RetrievalChannel.SEMANTIC);
        return new RankedItem(new Candidate(id, rank * 300_000L,
                rank * 300_000L + 60_000L, score, score, score, "片段 " + id, matched),
                score, ranks, matched, 0);
    }

    @Test
    void weakSingleChannelEvidenceExpandsBudget() {
        List<RankedItem> fused = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fused.add(item("w" + i, 0.016 - i * 0.001, 1, i + 1));
        }
        TopKPolicy.Decision decision = policy.decide(fused, 5);
        assertEquals(TopKPolicy.Action.EXPAND, decision.action());
        assertEquals(7, decision.topK());
    }

    @Test
    void plateauAtCutoffExpandsByOne() {
        List<RankedItem> fused = new ArrayList<>();
        fused.add(item("top", 0.049, 3, 1));
        for (int i = 1; i < 8; i++) {
            fused.add(item("p" + i, 0.020, 3, i + 1));
        }
        TopKPolicy.Decision decision = policy.decide(fused, 5);
        assertEquals(TopKPolicy.Action.EXPAND, decision.action());
        assertEquals(6, decision.topK());
    }

    @Test
    void dominantThreeChannelHeadShrinksBudget() {
        List<RankedItem> fused = new ArrayList<>();
        fused.add(item("dominant", 0.050, 3, 1));
        fused.add(item("second", 0.020, 3, 2));
        fused.add(item("third", 0.019, 3, 3));
        fused.add(item("fourth", 0.018, 3, 4));
        fused.add(item("fifth", 0.017, 3, 5));
        fused.add(item("sixth", 0.005, 3, 6));
        TopKPolicy.Decision decision = policy.decide(fused, 5);
        assertEquals(TopKPolicy.Action.SHRINK, decision.action());
        assertEquals(3, decision.topK());
    }

    @Test
    void emptyInputKeepsMinimumBudget() {
        TopKPolicy.Decision decision = policy.decide(List.of(), 5);
        assertEquals(TopKPolicy.Action.KEEP, decision.action());
        assertEquals(3, decision.topK());
    }
}
