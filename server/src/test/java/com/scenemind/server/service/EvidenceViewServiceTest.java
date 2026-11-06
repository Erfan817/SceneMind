package com.scenemind.server.service;

import com.scenemind.server.common.ErrorCode;
import com.scenemind.server.dto.FrameView;
import com.scenemind.server.dto.TimelineView;
import com.scenemind.server.dto.TranscriptView;
import com.scenemind.server.dto.VideoChunk;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceViewServiceTest {

    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final EvidenceViewService service = new EvidenceViewService(checkpointService);

    private VideoContext context() {
        return new VideoContext("demo://lecture", "目标", List.of(
                new VideoContext.VideoSegment(0, 60_000, "开场寒暄", List.of(), List.of()),
                new VideoContext.VideoSegment(60_000, 120_000,
                        "前序遍历先访问根节点。",
                        List.of("前序遍历：根节点、左子树、右子树"),
                        List.of("http://minio/frame_000007.jpg"))));
    }

    @Test
    void frameAtPicksCoveringSegmentAndItsFrame() {
        when(checkpointService.loadContext(1L)).thenReturn(context());

        FrameView view = service.frameAt(1L, 90_000);

        assertEquals(90_000, view.timestampMs());
        assertEquals(60_000, view.startMs());
        assertEquals(120_000, view.endMs());
        assertEquals("http://minio/frame_000007.jpg", view.frameUrl());
        assertEquals("ASR+OCR", view.source());
        assertEquals("前序遍历先访问根节点。", view.transcript());
        assertEquals(List.of("前序遍历：根节点、左子树、右子树"), view.ocrTexts());
    }

    @Test
    void frameAtFallsBackToNearestSegmentWithinMargin() {
        when(checkpointService.loadContext(1L)).thenReturn(context());

        FrameView view = service.frameAt(1L, 130_000);

        assertEquals(120_000, view.endMs());
    }

    @Test
    void frameAtRejectsTimestampFarFromAnySegment() {
        when(checkpointService.loadContext(1L)).thenReturn(context());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.frameAt(1L, 10 * 60_000L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void timelineUsesChunkSummaryAndCountsEvidenceCoverage() {
        VideoContext context = context();
        when(checkpointService.loadContext(1L)).thenReturn(context);
        when(checkpointService.loadChunks(1L)).thenReturn(List.of(
                new VideoChunk(0, 300_000, "第一章：前序遍历", List.of("前序", "遍历"),
                        context.segments(), List.of())));

        TimelineView view = service.timeline(1L);

        assertEquals(1L, view.mediaId());
        assertEquals(120_000, view.durationMs());
        assertEquals(1, view.entries().size());
        TimelineView.Entry entry = view.entries().get(0);
        assertEquals(0, entry.startMs());
        assertEquals(300_000, entry.endMs());
        assertEquals("第一章：前序遍历", entry.summary());
        assertEquals(2, entry.segmentCount());
        assertEquals(1, entry.ocrSegmentCount());
        assertEquals("http://minio/frame_000007.jpg", entry.frameUrl());
    }

    @Test
    void transcriptRangeSlicesOverlappingSegments() {
        when(checkpointService.loadContext(1L)).thenReturn(context());

        List<TranscriptView> views = service.transcriptRange(1L, 50_000, 130_000);

        assertEquals(2, views.size());
        assertEquals(50_000, views.get(0).startMs());
        assertEquals(60_000, views.get(0).endMs());
        assertEquals(60_000, views.get(1).startMs());
        assertEquals(120_000, views.get(1).endMs());
    }

    @Test
    void transcriptRangeRejectsEmptyWindow() {
        when(checkpointService.loadContext(1L)).thenReturn(context());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transcriptRange(1L, 200_000, 260_000));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }
}
