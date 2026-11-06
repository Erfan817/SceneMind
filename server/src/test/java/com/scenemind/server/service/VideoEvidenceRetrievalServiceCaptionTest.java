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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视觉语义通道（VLM 画面描述）的回归测试：画面里没有文字、只有描述时，
 * 片段仍应进入 VISUAL 通道并被关键词命中，命中结果里应携带描述文本。
 */
class VideoEvidenceRetrievalServiceCaptionTest {

    private static final Long MEDIA_ID = 1L;

    private final DeepSeekUtils deepSeek = mock(DeepSeekUtils.class);
    private final EmbeddingUtils embedding = mock(EmbeddingUtils.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final VideoEvidenceRetrievalService service = new VideoEvidenceRetrievalService(
            deepSeek, embedding, vectorStore, telemetry,
            new HybridRanker(RankingConfig.defaults()), new TopKPolicy());

    @Test
    void captionsEngageVisualChannelWithoutOcrText() {
        when(deepSeek.planRetrieval("演示的动作是什么")).thenReturn(
                new VideoRetrievalIntent("演示的动作是什么", List.of("动作"), List.of()));
        when(embedding.embed("演示的动作是什么")).thenReturn(List.of(1.0, 0.0));
        when(vectorStore.search(anyLong(), anyList(), anyInt())).thenReturn(List.of());
        when(vectorStore.searchSegments(anyLong(), anyList(), anyInt())).thenReturn(List.of());

        List<VideoChunk> chunks = List.of(new VideoChunk(
                0, 120_000L, "摘要", List.of("动作"),
                List.of(new VideoContext.VideoSegment(0, 60_000L, "", List.of(), List.of(),
                        List.of("画面里讲师正在演示一个伸展动作"))),
                List.of(0.0, 1.0)));

        List<VideoEvidenceHit> hits = service.search(MEDIA_ID, "演示的动作是什么", chunks);

        assertEquals(1, hits.size());
        VideoEvidenceHit hit = hits.get(0);
        assertTrue(hit.visualCaptions().contains("画面里讲师正在演示一个伸展动作"),
                "命中结果应携带 VLM 画面描述");
        assertTrue(hit.snippet().contains("演示"), "无 OCR 时片段文本应回退到描述");
        assertEquals("VISION", hit.source(), "仅有 VLM 描述时必须标为 VISION，不能伪装成 OCR 证据");
    }
}
