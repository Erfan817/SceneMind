package com.scenemind.server.service;

import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.ChannelRanking;
import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.RankingResult;
import com.scenemind.core.ranking.RetrievalChannel;
import com.scenemind.core.ranking.TopKPolicy;
import com.scenemind.server.dto.VideoChunk;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.dto.VideoEvidenceHit;
import com.scenemind.server.dto.VideoRetrievalIntent;
import com.scenemind.server.utils.DeepSeekUtils;
import com.scenemind.server.utils.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 查询感知的视频证据检索。
 *
 * <p><b>SceneMind 改造：</b>排序逻辑从这里剥离到 {@code com.scenemind.core.ranking} 纯函数内核，
 * 本类只负责「DTO 适配、外部依赖降级、埋点」三件事。原先的线性加权
 * {@code 0.6*semantic + 0.25*lexical + 0.15*visual} 换成 RRF 多通道融合，
 * 并由 {@link TopKPolicy} 动态决定召回预算；检索过程同时输出可解释诊断。</p>
 */
@Service
public class VideoEvidenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(VideoEvidenceRetrievalService.class);
    private static final int VECTOR_RECALL_LIMIT = 6;
    private static final int SEGMENT_VECTOR_RECALL_LIMIT = 24;
    private static final int SEGMENT_EMBEDDING_BATCH_SIZE = 16;
    private static final int MAX_SEGMENT_EMBEDDING_CHARS = 2_000;
    private static final int MAX_USER_HITS = 8;
    private static final int MAX_SNIPPET_LENGTH = 180;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final AgentTelemetry telemetry;
    private final HybridRanker hybridRanker;
    private final TopKPolicy topKPolicy;

    public VideoEvidenceRetrievalService(DeepSeekUtils deepSeekUtils,
                                         EmbeddingUtils embeddingUtils,
                                         QdrantVectorStore vectorStore,
                                         AgentTelemetry telemetry,
                                         HybridRanker hybridRanker,
                                         TopKPolicy topKPolicy) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.telemetry = telemetry;
        this.hybridRanker = hybridRanker;
        this.topKPolicy = topKPolicy;
    }

    public List<VideoContext.VideoSegment> retrieve(Long mediaId,
                                                     String goal,
                                                     List<VideoChunk> chunks) {
        return rank(mediaId, goal, chunks).stream()
                .map(ScoredSegment::segment)
                .toList();
    }

    public List<VideoEvidenceHit> search(Long mediaId,
                                         String query,
                                         List<VideoChunk> chunks) {
        return rank(mediaId, query, chunks).stream()
                .limit(MAX_USER_HITS)
                .map(this::toHit)
                .toList();
    }

    private List<ScoredSegment> rank(Long mediaId, String goal, List<VideoChunk> chunks) {
        List<VideoChunk> safeChunks = chunks == null ? List.of() : chunks;
        VideoRetrievalIntent intent = retrievalIntent(goal);
        List<Double> queryEmbedding = embed(intent.semanticQuery());
        Map<String, Double> vectorScores = vectorScores(mediaId, queryEmbedding);
        // 分段级语义：Qdrant 可用时优先用 segment 命中，缺失时回退到 chunk 分数。
        // 这样 5 分钟 chunk 内的 5 个 60s 片段不再共享同一个语义名次。
        Map<String, Double> segmentScores = segmentVectorScores(mediaId, queryEmbedding);

        // 1. 把 Spring DTO 适配成内核 Candidate：一个原始片段一条证据。
        Map<String, VideoContext.VideoSegment> segmentsById = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        int captionSegments = 0;
        for (VideoChunk chunk : safeChunks) {
            double chunkSemanticScore = chunkSemanticScore(intent, queryEmbedding, vectorScores, chunk);
            for (VideoContext.VideoSegment segment : chunk.rawSegments()) {
                String id = segment.startMs() + ":" + segment.endMs();
                String ocr = String.join(" ", normalizedOcrTexts(segment));
                String captions = String.join(" ", normalizedCaptions(segment));
                double semanticScore = segmentScores.getOrDefault(id, chunkSemanticScore);
                double lexicalScore = termScore(intent.keywords(), segment.transcript());
                // 视觉通道 = OCR 关键词命中 与 关键帧 VLM 描述命中 取大：
                // 画面里没有文字但 VLM 描述出了主体/动作/图表时，片段仍能借到视觉通道名次。
                double ocrScore = termScore(intent.visualKeywords(), ocr);
                double captionScore = Math.max(
                        termScore(intent.visualKeywords(), captions),
                        termScore(intent.keywords(), captions));
                double visualScore = Math.max(ocrScore, captionScore);
                List<RetrievalChannel> present = new ArrayList<>();
                if (!segment.transcript().isBlank()) {
                    present.add(RetrievalChannel.SEMANTIC);
                    present.add(RetrievalChannel.LEXICAL);
                }
                if (!ocr.isBlank() || !captions.isBlank()) present.add(RetrievalChannel.VISUAL);
                if (!captions.isBlank()) captionSegments++;
                candidates.add(new Candidate(id, segment.startMs(), segment.endMs(),
                        semanticScore, lexicalScore, visualScore,
                        (segment.transcript() + " " + ocr + " " + captions).trim(), present));
                segmentsById.putIfAbsent(id, segment);
            }
        }

        // 2. 三通道独立召回 -> RRF 融合 -> 自适应预算 -> 覆盖选择。
        // 每个通道只接收确实拥有该通道证据的候选：没有 OCR 的片段不能从视觉通道借名次，
        // 否则 matchedChannels 恒为 3，TopKPolicy 的弱证据扩召回永远不可达。
        List<ChannelRanking> channels = Arrays.stream(RetrievalChannel.values())
                .map(channel -> new ChannelRanking(channel, candidates.stream()
                        .filter(candidate -> candidate.channelsPresent().contains(channel))
                        .toList()))
                .toList();
        HybridRanker.Fusion fusion = hybridRanker.fuse(channels);
        TopKPolicy.Decision decision = topKPolicy.decide(
                fusion.items(), hybridRanker.config().maxResults());
        RankingResult ranked = hybridRanker.select(fusion, decision.topK());

        if (!ranked.items().isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore", ranked.items().get(0).fusedScore());
            telemetry.incrementCurrent("retrievalSegments", ranked.items().size());
        }
        telemetry.incrementCurrent("retrievalDuplicatesSuppressed",
                ranked.diagnostics().duplicatesSuppressed());
        telemetry.valueCurrent("retrievalDynamicTopK", decision.topK());
        // 逐条可解释诊断落到 trace，供 /analysis/agent-trace 与前端调试面板消费。
        telemetry.sectionCurrent("retrieval", retrievalDiagnostics(ranked, decision, segmentScores.size(), captionSegments));
        if (log.isDebugEnabled()) {
            log.debug("retrieval decision: {} | {}", decision.reason(), ranked.diagnostics().notes());
        }

        return ranked.items().stream()
                .map(item -> {
                    VideoContext.VideoSegment segment = segmentsById.get(item.candidate().id());
                    return new ScoredSegment(segment, item.fusedScore(),
                            item.candidate().lexicalScore(), item.candidate().visualScore());
                })
                .filter(item -> item.segment() != null)
                .toList();
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        index(mediaId, chunks, List.of());
    }

    /**
     * 同时写入 chunk 与 segment 两级向量。segment 写入失败不影响 chunk 级检索：
     * 线上仍会回退到原来的 chunk 语义分，只是失去段级分辨率。
     */
    public void index(Long mediaId, List<VideoChunk> chunks,
                      List<VideoContext.VideoSegment> segments) {
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可走内存向量和关键词，别让检索基础设施拖垮分析主链路。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
            log.warn("vector_index_failed mediaId={} chunks={}", mediaId, chunks.size(), e);
        }
        indexSegments(mediaId, segments);
    }

    /** 把 60s 原始片段单独嵌入，供段级语义精排使用。 */
    public void indexSegments(Long mediaId, List<VideoContext.VideoSegment> segments) {
        if (mediaId == null || segments == null || segments.isEmpty()) return;
        try {
            List<VideoContext.VideoSegment> usable = segments.stream()
                    .filter(segment -> !segmentText(segment).isBlank())
                    .toList();
            List<QdrantVectorStore.SegmentVector> vectors = new ArrayList<>();
            for (int start = 0; start < usable.size(); start += SEGMENT_EMBEDDING_BATCH_SIZE) {
                int end = Math.min(usable.size(), start + SEGMENT_EMBEDDING_BATCH_SIZE);
                List<VideoContext.VideoSegment> batch = usable.subList(start, end);
                List<List<Double>> embeddings = embeddingUtils.embedBatch(
                        batch.stream().map(this::segmentText).toList());
                for (int i = 0; i < batch.size() && i < embeddings.size(); i++) {
                    List<Double> embedding = embeddings.get(i);
                    if (embedding.isEmpty()) continue;
                    VideoContext.VideoSegment segment = batch.get(i);
                    vectors.add(new QdrantVectorStore.SegmentVector(
                            segment.startMs(), segment.endMs(), embedding));
                }
            }
            if (vectors.isEmpty()) return;
            vectorStore.upsertSegments(mediaId, vectors);
            telemetry.incrementCurrent("segmentVectorWrites", vectors.size());
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("segmentVectorFallbacks", 1);
            log.warn("segment_vector_index_failed mediaId={} segments={}", mediaId, segments.size(), e);
        }
    }

    /** 把 RankingResult 的逐条依据转换成可 JSON 化的诊断对象。 */
    private Map<String, Object> retrievalDiagnostics(RankingResult ranked,
                                                      TopKPolicy.Decision decision,
                                                      int segmentSemanticHits,
                                                      int captionSegments) {
        // trace 会写 Redis 并保留 7 天：只存前 N 条明细，避免长视频把 trace 撑爆。
        int maxDiagnosticItems = 20;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("topK", hybridRanker.config().maxResults());
        diagnostics.put("dynamicTopK", decision.topK());
        diagnostics.put("action", decision.action().name());
        diagnostics.put("reason", decision.reason());
        diagnostics.put("candidatesSeen", ranked.diagnostics().candidatesSeen());
        diagnostics.put("segmentSemanticHits", segmentSemanticHits);
        diagnostics.put("semanticResolution", segmentSemanticHits > 0 ? "segment" : "chunk");
        diagnostics.put("captionSegments", captionSegments);
        diagnostics.put("duplicatesSuppressed", ranked.diagnostics().duplicatesSuppressed());
        diagnostics.put("windowsCovered", ranked.diagnostics().windowsCovered());
        diagnostics.put("secondPassFills", ranked.diagnostics().secondPassFills());
        diagnostics.put("channelsUsed", ranked.diagnostics().channelsUsed().stream()
                .map(Enum::name).toList());
        diagnostics.put("notes", ranked.diagnostics().notes());
        diagnostics.put("itemsTotal", ranked.items().size());
        diagnostics.put("itemsTruncated", ranked.items().size() > maxDiagnosticItems);
        diagnostics.put("items", ranked.items().stream().limit(maxDiagnosticItems).map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.candidate().id());
            row.put("fusedScore", item.fusedScore());
            row.put("selectionPass", item.selectionPass());
            Map<String, Integer> ranks = new LinkedHashMap<>();
            item.channelRanks().forEach((channel, rank) -> ranks.put(channel.name(), rank));
            row.put("channelRanks", ranks);
            row.put("matchedChannels", item.matchedChannels().stream().map(Enum::name).toList());
            return row;
        }).toList());
        return diagnostics;
    }

    private double chunkSemanticScore(VideoRetrievalIntent intent,
                                      List<Double> queryEmbedding,
                                      Map<String, Double> vectorScores,
                                      VideoChunk chunk) {
        Double remoteScore = vectorScores.get(chunkKey(chunk));
        return remoteScore == null
                ? cosine(queryEmbedding, chunk.embedding())
                : remoteScore;
    }

    private Map<String, Double> segmentVectorScores(Long mediaId, List<Double> queryEmbedding) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (mediaId == null || queryEmbedding.isEmpty()) return scores;
        try {
            vectorStore.searchSegments(mediaId, queryEmbedding, SEGMENT_VECTOR_RECALL_LIMIT)
                    .forEach(hit -> scores.merge(
                            hit.startMs() + ":" + hit.endMs(), hit.score(), Math::max));
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("segmentVectorFallbacks", 1);
        }
        return scores;
    }

    private String segmentText(VideoContext.VideoSegment segment) {
        if (segment == null) return "";
        String text = (segment.transcript() + " "
                + String.join(" ", normalizedOcrTexts(segment)) + " "
                + String.join(" ", normalizedCaptions(segment))).trim();
        return text.length() <= MAX_SEGMENT_EMBEDDING_CHARS
                ? text
                : text.substring(0, MAX_SEGMENT_EMBEDDING_CHARS);
    }

    private Map<String, Double> vectorScores(Long mediaId, List<Double> queryEmbedding) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (mediaId == null || queryEmbedding.isEmpty()) return scores;
        try {
            vectorStore.search(mediaId, queryEmbedding, VECTOR_RECALL_LIMIT).forEach(hit ->
                    scores.put(hit.startMs() + ":" + hit.endMs(), hit.score()));
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
        return scores;
    }

    private VideoEvidenceHit toHit(ScoredSegment result) {
        VideoContext.VideoSegment segment = result.segment();
        List<String> ocrTexts = normalizedOcrTexts(segment);
        List<String> captions = normalizedCaptions(segment);
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = !ocrTexts.isEmpty();
        boolean hasCaptions = !captions.isEmpty();
        // 来源只描述可引用证据通道：VLM 画面描述只参与召回，不能伪装成 OCR/ASR。
        String source = hasTranscript && hasOcr
                ? "ASR+OCR"
                : hasOcr ? "OCR"
                : hasTranscript ? "ASR"
                : hasCaptions ? "VISION"
                : "时间片段";
        String ocrText = String.join(" ", ocrTexts);
        String preferred = result.visualScore() > result.transcriptScore() ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = hasOcr ? ocrText : segment.transcript();
        if (preferred.isBlank() && hasCaptions) preferred = String.join(" ", captions);
        if (preferred.isBlank()) preferred = "该时间段暂无可展示文本";
        return new VideoEvidenceHit(
                segment.startMs(),
                segment.endMs(),
                source,
                abbreviate(preferred),
                segment.transcript(),
                ocrTexts,
                captions);
    }

    private List<String> normalizedOcrTexts(VideoContext.VideoSegment segment) {
        return segment.ocrTexts().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
    }

    private List<String> normalizedCaptions(VideoContext.VideoSegment segment) {
        return segment.visualCaptions() == null ? List.of() : segment.visualCaptions().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
    }

    private double termScore(List<String> terms, String content) {
        String normalizedContent = normalize(content);
        List<String> normalizedTerms = terms.stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
        long matched = normalizedTerms.stream().filter(normalizedContent::contains).count();
        return normalizedTerms.isEmpty() ? 0 : (double) matched / normalizedTerms.size();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private VideoRetrievalIntent retrievalIntent(String goal) {
        try {
            VideoRetrievalIntent intent = deepSeekUtils.planRetrieval(goal);
            if (!intent.semanticQuery().isBlank()) return intent;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("retrievalIntentFallbacks", 1);
        }
        return new VideoRetrievalIntent(goal, fallbackTerms(goal), fallbackTerms(goal));
    }

    private List<Double> embed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            return List.of();
        }
    }

    private String chunkKey(VideoChunk chunk) {
        return chunk.startTime() + ":" + chunk.endTime();
    }

    private List<String> fallbackTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<String> terms = Arrays.stream(query.trim().split("[\\s，。！？、,.;:：；!?]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(8)
                .toList();
        return terms.isEmpty() ? List.of(query.trim()) : terms;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_SNIPPET_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    private record ScoredSegment(
            VideoContext.VideoSegment segment,
            double score,
            double transcriptScore,
            double visualScore
    ) {
    }
}
