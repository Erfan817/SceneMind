package com.scenemind.server.service;

import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.TopKPolicy;
import com.scenemind.server.dto.VideoChunk;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.dto.VideoEvidenceHit;
import com.scenemind.server.dto.VideoRetrievalIntent;
import com.scenemind.server.utils.DeepSeekUtils;
import com.scenemind.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * segment 级语义检索的回归测试。
 *
 * <p>三片段设计：seg1 的语义分最高但词法最低、seg2 的词法分最高但语义分最低。
 * chunk 级语义时 seg1/seg2/seg3 共享同一 chunk 分，词法主导 → seg2 胜出；
 * 命中 segment 向量后语义主导 → seg1 胜出。排序翻转必须由段级向量驱动，而不是巧合。</p>
 */
class VideoEvidenceRetrievalServiceSegmentSemanticTest {

    private static final Long MEDIA_ID = 1L;

    private final DeepSeekUtils deepSeek = mock(DeepSeekUtils.class);
    private final EmbeddingUtils embedding = mock(EmbeddingUtils.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final VideoEvidenceRetrievalService service = new VideoEvidenceRetrievalService(
            deepSeek, embedding, vectorStore, telemetry,
            new HybridRanker(RankingConfig.defaults()), new TopKPolicy());

    private List<VideoChunk> chunks() {
        return List.of(new VideoChunk(
                0, 180_000L,
                "整段摘要",
                List.of("遍历"),
                List.of(
                        new VideoContext.VideoSegment(0, 60_000L, "开场寒暄", List.of(), List.of()),
                        new VideoContext.VideoSegment(60_000L, 120_000L,
                                "前序遍历先访问根节点", List.of(), List.of()),
                        new VideoContext.VideoSegment(120_000L, 180_000L,
                                "后序遍历先访问左右子树", List.of(), List.of())),
                // chunk 语义向量与查询正交：证明段级命中不是由 chunk 分带来的
                List.of(0.0, 1.0)));
    }

    private void stubQueryAndEmbedding() {
        when(deepSeek.planRetrieval("遍历的顺序是什么")).thenReturn(
                new VideoRetrievalIntent("遍历的顺序是什么", List.of("遍历"), List.of()));
        when(embedding.embed("遍历的顺序是什么")).thenReturn(List.of(1.0, 0.0));
        when(vectorStore.search(anyLong(), anyList(), anyInt())).thenReturn(List.of());
    }

    @Test
    void fallsBackToChunkSemanticWhenSegmentVectorsUnavailable() {
        stubQueryAndEmbedding();
        when(vectorStore.searchSegments(anyLong(), anyList(), anyInt())).thenReturn(List.of());

        List<VideoEvidenceHit> hits = service.search(MEDIA_ID, "遍历的顺序是什么", chunks());

        // 没有段级向量时，词法通道主导：含「遍历」的第二段胜出。
        assertEquals(60_000L, hits.get(0).startMs());
    }

    @Test
    void segmentVectorsFlipWinnerTowardsSemanticallyRelevantSegment() {
        stubQueryAndEmbedding();
        when(vectorStore.searchSegments(anyLong(), anyList(), anyInt())).thenReturn(List.of(
                new QdrantVectorStore.SegmentHit(0, 60_000L, 0.95),
                new QdrantVectorStore.SegmentHit(120_000L, 180_000L, 0.20),
                new QdrantVectorStore.SegmentHit(60_000L, 120_000L, 0.10)));

        List<VideoEvidenceHit> hits = service.search(MEDIA_ID, "遍历的顺序是什么", chunks());

        // 段级语义把第一名从「词法命中」翻到「语义相关」的片段。
        assertEquals(0L, hits.get(0).startMs());
        verify(vectorStore).searchSegments(anyLong(), anyList(), anyInt());
    }
}
