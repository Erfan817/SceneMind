package com.scenemind.server.service;

import com.scenemind.server.common.ErrorCode;
import com.scenemind.server.dto.FrameView;
import com.scenemind.server.dto.TimelineView;
import com.scenemind.server.dto.TranscriptView;
import com.scenemind.server.dto.VideoChunk;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 证据画面视图：把 Checkpoint 里的 {@code VideoContext} 转成 Agent 可以直接核验的
 * 单帧视图与转写片段。这里只做"查"与"切"，不重新做证据校验——校验仍在服务端
 * {@code EvidenceAuditor} / {@code Critic} 完成。
 */
@Service
public class EvidenceViewService {

    /** 未命中时间戳时允许的邻近距离：60 秒，与 ASR 分段粒度一致。 */
    private static final long NEAR_MARGIN_MS = 60_000L;

    private final AgentCheckpointService checkpointService;

    public EvidenceViewService(AgentCheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    public FrameView frameAt(Long mediaId, long timestampMs) {
        if (timestampMs < 0) throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "timestampMs 不能为负");
        VideoContext context = requireContext(mediaId);
        VideoContext.VideoSegment segment = nearestSegment(context, timestampMs);
        if (segment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "没有覆盖或邻近 " + timestampMs + "ms 的片段");
        }
        List<String> ocrTexts = segment.ocrTexts() == null ? List.of() : segment.ocrTexts();
        String transcript = segment.transcript() == null ? "" : segment.transcript();
        String frameUrl = segment.evidenceFrames() == null ? "" : segment.evidenceFrames().stream()
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse("");
        return new FrameView(timestampMs, segment.startMs(), segment.endMs(),
                frameUrl, ocrTexts, transcript, source(transcript, ocrTexts));
    }

    /**
     * 按 5 分钟章节返回时间轴。chunk checkpoint 存在时用它的摘要/关键词；
     * 旧数据没有 chunk 时退化为按 5 分钟窗口聚合原始片段，保证工具始终可用。
     */
    public TimelineView timeline(Long mediaId) {
        VideoContext context = requireContext(mediaId);
        long durationMs = context.segments().stream()
                .filter(segment -> segment != null)
                .mapToLong(VideoContext.VideoSegment::endMs)
                .max()
                .orElse(0L);
        List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
        List<TimelineView.Entry> entries = new ArrayList<>();

        if (chunks != null && !chunks.isEmpty()) {
            for (VideoChunk chunk : chunks) {
                List<VideoContext.VideoSegment> within = context.segments().stream()
                        .filter(segment -> segment != null
                                && segment.startMs() >= chunk.startTime()
                                && segment.startMs() < chunk.endTime())
                        .toList();
                if (within.isEmpty()) continue;
                entries.add(entry(chunk.startTime(), chunk.endTime(),
                        chunk.segmentSummary(), chunk.keywords(), within));
            }
        } else {
            Map<Long, List<VideoContext.VideoSegment>> windows = new TreeMap<>();
            for (VideoContext.VideoSegment segment : context.segments()) {
                if (segment == null) continue;
                long windowStart = segment.startMs() / 300_000L * 300_000L;
                windows.computeIfAbsent(windowStart, ignored -> new ArrayList<>()).add(segment);
            }
            for (Map.Entry<Long, List<VideoContext.VideoSegment>> window : windows.entrySet()) {
                List<VideoContext.VideoSegment> within = window.getValue();
                String summary = within.stream()
                        .map(VideoContext.VideoSegment::transcript)
                        .filter(text -> text != null && !text.isBlank())
                        .findFirst()
                        .orElse("");
                entries.add(entry(window.getKey(), window.getKey() + 300_000L,
                        summary, List.of(), within));
            }
        }
        return new TimelineView(mediaId, durationMs, entries);
    }

    private TimelineView.Entry entry(long startMs,
                                      long endMs,
                                      String summary,
                                      List<String> keywords,
                                      List<VideoContext.VideoSegment> segments) {
        int ocrCount = 0;
        int captionCount = 0;
        String frameUrl = "";
        for (VideoContext.VideoSegment segment : segments) {
            if (segment.ocrTexts() != null && segment.ocrTexts().stream()
                    .anyMatch(text -> text != null && !text.isBlank())) {
                ocrCount++;
            }
            if (segment.visualCaptions() != null && segment.visualCaptions().stream()
                    .anyMatch(text -> text != null && !text.isBlank())) {
                captionCount++;
            }
            if (frameUrl.isBlank() && segment.evidenceFrames() != null) {
                frameUrl = segment.evidenceFrames().stream()
                        .filter(url -> url != null && !url.isBlank())
                        .findFirst()
                        .orElse("");
            }
        }
        String safeSummary = summary == null ? "" : summary.trim();
        if (safeSummary.length() > 180) safeSummary = safeSummary.substring(0, 180) + "...";
        return new TimelineView.Entry(startMs, endMs, safeSummary,
                keywords == null ? List.of() : keywords,
                segments.size(), ocrCount, captionCount, frameUrl);
    }

    public List<TranscriptView> transcriptRange(Long mediaId, long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "startMs < endMs 且均不能为负");
        }
        VideoContext context = requireContext(mediaId);
        List<TranscriptView> result = new ArrayList<>();
        for (VideoContext.VideoSegment segment : context.segments()) {
            if (segment == null) continue;
            long overlapStart = Math.max(startMs, segment.startMs());
            long overlapEnd = Math.min(endMs, segment.endMs());
            if (overlapStart >= overlapEnd) continue;
            List<String> ocrTexts = segment.ocrTexts() == null ? List.of() : segment.ocrTexts();
            result.add(new TranscriptView(overlapStart, overlapEnd,
                    segment.transcript() == null ? "" : segment.transcript(), ocrTexts));
        }
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该时间范围内没有转写片段");
        }
        return List.copyOf(result);
    }

    private VideoContext requireContext(Long mediaId) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null || context.segments() == null || context.segments().isEmpty()) {
            throw new VideoContextNotReadyException();
        }
        return context;
    }

    /** 优先覆盖该时间戳的片段；否则取 60 秒内最近的一个。 */
    private VideoContext.VideoSegment nearestSegment(VideoContext context, long timestampMs) {
        VideoContext.VideoSegment covering = null;
        VideoContext.VideoSegment nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (VideoContext.VideoSegment segment : context.segments()) {
            if (segment == null) continue;
            if (timestampMs >= segment.startMs() && timestampMs < segment.endMs()) {
                covering = segment;
                break;
            }
            long distance = distance(timestampMs, segment);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = segment;
            }
        }
        if (covering != null) return covering;
        return nearestDistance <= NEAR_MARGIN_MS ? nearest : null;
    }

    private long distance(long timestampMs, VideoContext.VideoSegment segment) {
        if (timestampMs < segment.startMs()) return segment.startMs() - timestampMs;
        if (timestampMs >= segment.endMs()) return timestampMs - segment.endMs() + 1;
        return 0;
    }

    private String source(String transcript, List<String> ocrTexts) {
        boolean hasAsr = !transcript.isBlank();
        boolean hasOcr = ocrTexts.stream().anyMatch(text -> text != null && !text.isBlank());
        if (hasAsr && hasOcr) return "ASR+OCR";
        if (hasOcr) return "OCR";
        if (hasAsr) return "ASR";
        return "时间片段";
    }
}
