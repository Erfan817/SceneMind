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

        // 1. 把 Spring DTO 适配成内核 Candidate：一个原始片段一条证据。
        Map<String, VideoContext.VideoSegment> segmentsById = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        for (VideoChunk chunk : safeChunks) {
            double semanticScore = semanticScore(intent, queryEmbedding, vectorScores, chunk);
            for (VideoContext.VideoSegment segment : chunk.rawSegments()) {
                String id = segment.startMs() + ":" + segment.endMs();
                String ocr = String.join(" ", normalizedOcrTexts(segment));
                double lexicalScore = termScore(intent.keywords(), segment.transcript());
                double visualScore = termScore(intent.visualKeywords(), ocr);
                List<RetrievalChannel> present = new ArrayList<>();
                if (!segment.transcript().isBlank()) {
                    present.add(RetrievalChannel.SEMANTIC);
                    present.add(RetrievalChannel.LEXICAL);
                }
                if (!ocr.isBlank()) present.add(RetrievalChannel.VISUAL);
                candidates.add(new Candidate(id, segment.startMs(), segment.endMs(),
                        semanticScore, lexicalScore, visualScore,
                        segment.transcript() + " " + ocr, present));
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
        telemetry.sectionCurrent("retrieval", retrievalDiagnostics(ranked, decision));
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
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可走内存向量和关键词，别让检索基础设施拖垮分析主链路。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
            log.warn("vector_index_failed mediaId={} chunks={}", mediaId, chunks.size(), e);
        }
    }

    /** 把 RankingResult 的逐条依据转换成可 JSON 化的诊断对象。 */
    private Map<String, Object> retrievalDiagnostics(RankingResult ranked,
                                                      TopKPolicy.Decision decision) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("topK", hybridRanker.config().maxResults());
        diagnostics.put("dynamicTopK", decision.topK());
        diagnostics.put("action", decision.action().name());
        diagnostics.put("reason", decision.reason());
        diagnostics.put("candidatesSeen", ranked.diagnostics().candidatesSeen());
        diagnostics.put("duplicatesSuppressed", ranked.diagnostics().duplicatesSuppressed());
        diagnostics.put("windowsCovered", ranked.diagnostics().windowsCovered());
        diagnostics.put("secondPassFills", ranked.diagnostics().secondPassFills());
        diagnostics.put("channelsUsed", ranked.diagnostics().channelsUsed().stream()
                .map(Enum::name).toList());
        diagnostics.put("notes", ranked.diagnostics().notes());
        diagnostics.put("items", ranked.items().stream().map(item -> {
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

    private double semanticScore(VideoRetrievalIntent intent,
                                 List<Double> queryEmbedding,
                                 Map<String, Double> vectorScores,
                                 VideoChunk chunk) {
        Double remoteScore = vectorScores.get(chunkKey(chunk));
        return remoteScore == null
                ? cosine(queryEmbedding, chunk.embedding())
                : remoteScore;
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
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = !ocrTexts.isEmpty();
        String source = hasTranscript && hasOcr
                ? "ASR+OCR"
                : hasOcr ? "OCR" : hasTranscript ? "ASR" : "时间片段";
        String ocrText = String.join(" ", ocrTexts);
        String preferred = result.visualScore() > result.transcriptScore() ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = hasOcr ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = "该时间段暂无可展示文本";
        return new VideoEvidenceHit(
                segment.startMs(),
                segment.endMs(),
                source,
                abbreviate(preferred),
                segment.transcript(),
                ocrTexts);
    }

    private List<String> normalizedOcrTexts(VideoContext.VideoSegment segment) {
        return segment.ocrTexts().stream()
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
