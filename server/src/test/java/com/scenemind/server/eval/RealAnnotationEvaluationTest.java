package com.scenemind.server.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenemind.core.evidence.EvidenceAuditor;
import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.ChannelRanking;
import com.scenemind.core.ranking.HybridRanker;
import com.scenemind.core.ranking.LinearBaselineRanker;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.RankingResult;
import com.scenemind.core.ranking.RetrievalChannel;
import com.scenemind.core.ranking.TextSimilarity;
import com.scenemind.core.ranking.TopKPolicy;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实标注集评测入口。
 *
 * <p>默认跳过：把标注文件放到 {@code server/src/main/resources/evaluation/real-annotations.json}
 * 后，运行 {@code bash ./mvnw -Dtest=RealAnnotationEvaluationTest test} 即会自动启用。
 * 数据格式与采集流程见 {@code docs/EVALUATION_DATA.md}，示例见
 * {@code real-annotations.example.json}。</p>
 *
 * <p><b>这是代理评测</b>：没有在线 embedding 服务时，语义通道用字符 bigram 相似度代理，
 * 词法/视觉通道用 query bigram 覆盖率代理。它验证的是「同一份真实文本上，RRF 与线性加权
 * 的排序差异」，不能替代线上真实召回率。要验证线上效果，请用
 * {@code scripts/smoke-test.sh} 或在线 A/B。</p>
 */
class RealAnnotationEvaluationTest {

    private static final String REAL_ANNOTATIONS = "evaluation/real-annotations.json";
    private static final int TOP_K = 8;
    private static final long WINDOW_MS = 300_000L;
    private static final double REDUNDANCY_SIMILARITY = 0.82;

    record AnnotationFile(int version, List<AnnotationSet> sets) {
    }

    record AnnotationSet(String name, String source, String goal,
                         List<Segment> segments, List<Query> queries) {
    }

    record Segment(long startMs, long endMs, String transcript, List<String> ocrTexts) {
    }

    record Query(String id, String text, List<Relevant> relevant) {
    }

    record Relevant(long startMs, long endMs) {
    }

    @Test
    void evaluateRealAnnotationsWhenPresent() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream(REAL_ANNOTATIONS);
        assumeTrue(in != null, "未找到 " + REAL_ANNOTATIONS
                + "，跳过真实标注集评测；格式见 docs/EVALUATION_DATA.md");

        AnnotationFile file = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(in, AnnotationFile.class);
        assumeTrue(file.sets() != null && !file.sets().isEmpty(), "标注文件为空");

        HybridRanker hybrid = new HybridRanker(RankingConfig.defaults());
        TopKPolicy policy = new TopKPolicy();
        LinearBaselineRanker baseline = new LinearBaselineRanker();

        System.out.println();
        System.out.println("# 真实标注集评测（代理通道）");
        System.out.println("> 语义通道 = 字符 bigram Jaccard；词法/视觉通道 = query bigram 覆盖率。");
        System.out.println("> 这是真实文本上的排序对比，不是线上 embedding 召回率；边界见 docs/EVALUATION_DATA.md。");
        System.out.println();

        List<Double> hybridRecall = new ArrayList<>();
        List<Double> baselineRecall = new ArrayList<>();
        List<Double> hybridCoverage = new ArrayList<>();
        List<Double> hybridRedundancy = new ArrayList<>();

        for (AnnotationSet set : file.sets()) {
            if (set.segments() == null || set.segments().isEmpty()
                    || set.queries() == null || set.queries().isEmpty()) {
                continue;
            }
            System.out.println("## " + set.name());
            System.out.println("| query | 相关片段 | RRF Recall@8 | 线性 Recall@8 | RRF 覆盖 | RRF 冗余 |");
            System.out.println("| --- | ---: | ---: | ---: | ---: | ---: |");
            for (Query query : set.queries()) {
                List<Candidate> candidates = scoredCandidates(set, query);
                Set<String> relevantIds = query.relevant() == null ? Set.of()
                        : query.relevant().stream()
                        .map(r -> r.startMs() + ":" + r.endMs())
                        .collect(Collectors.toCollection(LinkedHashSet::new));

                HybridRanker.Fusion fusion = hybrid.fuse(channelRankings(candidates));
                TopKPolicy.Decision decision = policy.decide(fusion.items(), TOP_K);
                RankingResult hybridResult = hybrid.select(fusion, decision.topK());
                List<Candidate> hybridTop = hybridResult.candidates();
                List<Candidate> baselineTop = baseline.rank(candidates, TOP_K);

                double hRecall = recall(hybridTop, relevantIds);
                double bRecall = recall(baselineTop, relevantIds);
                double hCoverage = coverage(hybridTop, query.relevant());
                double hRedundancy = redundancy(hybridTop);

                hybridRecall.add(hRecall);
                baselineRecall.add(bRecall);
                hybridCoverage.add(hCoverage);
                hybridRedundancy.add(hRedundancy);

                System.out.printf(Locale.ROOT,
                        "| %s | %d | %.3f | %.3f | %.3f | %.3f |%n",
                        query.id(), relevantIds.size(), hRecall, bRecall, hCoverage, hRedundancy);
            }
            System.out.println();
        }

        System.out.println("## 汇总");
        System.out.printf(Locale.ROOT, "- 平均 RRF Recall@8：%.3f%n", average(hybridRecall));
        System.out.printf(Locale.ROOT, "- 平均线性 Recall@8：%.3f%n", average(baselineRecall));
        System.out.printf(Locale.ROOT, "- 平均时间覆盖率：%.3f%n", average(hybridCoverage));
        System.out.printf(Locale.ROOT, "- 平均冗余率：%.3f%n", average(hybridRedundancy));
        if (average(hybridRecall) + 1e-9 < average(baselineRecall)) {
            System.out.println("- 注意：该数据集上 RRF 平均召回低于线性基线，请检查标注质量或通道代理是否失真。");
        }
    }

    private static List<Candidate> scoredCandidates(AnnotationSet set, Query query) {
        String normalizedQuery = EvidenceAuditor.normalize(query.text());
        return set.segments().stream().map(segment -> {
            String transcript = segment.transcript() == null ? "" : segment.transcript();
            String ocr = segment.ocrTexts() == null ? "" : String.join(" ", segment.ocrTexts());
            String text = (transcript + " " + ocr).trim();
            List<RetrievalChannel> channels = new ArrayList<>();
            if (!transcript.isBlank()) {
                channels.add(RetrievalChannel.SEMANTIC);
                channels.add(RetrievalChannel.LEXICAL);
            }
            if (!ocr.isBlank()) {
                channels.add(RetrievalChannel.VISUAL);
            }
            return new Candidate(
                    segment.startMs() + ":" + segment.endMs(),
                    segment.startMs(), segment.endMs(),
                    TextSimilarity.jaccard(query.text(), text),
                    bigramCoverage(normalizedQuery, transcript),
                    bigramCoverage(normalizedQuery, ocr),
                    text, channels);
        }).toList();
    }

    private static List<ChannelRanking> channelRankings(List<Candidate> candidates) {
        return Arrays.stream(RetrievalChannel.values())
                .map(channel -> new ChannelRanking(channel, candidates.stream()
                        .filter(candidate -> candidate.channelsPresent().contains(channel))
                        .toList()))
                .toList();
    }

    /** query 的字符 bigram 被目标文本覆盖的比例；无 bigram 时退化为包含判断。 */
    private static double bigramCoverage(String normalizedQuery, String text) {
        String normalizedText = EvidenceAuditor.normalize(text);
        if (normalizedQuery.isEmpty() || normalizedText.isEmpty()) return 0.0;
        Set<String> grams = TextSimilarity.bigrams(normalizedQuery);
        if (grams.isEmpty()) return normalizedText.contains(normalizedQuery) ? 1.0 : 0.0;
        long hit = grams.stream().filter(normalizedText::contains).count();
        return (double) hit / grams.size();
    }

    private static double recall(List<Candidate> top, Set<String> relevantIds) {
        if (relevantIds.isEmpty()) return 1.0;
        long hit = top.stream().map(Candidate::id).filter(relevantIds::contains).count();
        return (double) hit / relevantIds.size();
    }

    private static double coverage(List<Candidate> top, List<Relevant> relevant) {
        if (relevant == null || relevant.isEmpty()) return 1.0;
        Set<Long> relevantWindows = relevant.stream()
                .map(r -> (r.startMs() + (r.endMs() - r.startMs()) / 2) / WINDOW_MS)
                .collect(Collectors.toSet());
        Set<Long> hitWindows = top.stream()
                .filter(candidate -> relevant.stream().anyMatch(r ->
                        candidate.midMs() >= r.startMs() && candidate.midMs() < r.endMs()))
                .map(candidate -> candidate.midMs() / WINDOW_MS)
                .collect(Collectors.toSet());
        return (double) hitWindows.size() / relevantWindows.size();
    }

    private static double redundancy(List<Candidate> top) {
        int pairs = 0;
        int duplicated = 0;
        for (int i = 0; i < top.size(); i++) {
            for (int j = i + 1; j < top.size(); j++) {
                pairs++;
                Candidate left = top.get(i);
                Candidate right = top.get(j);
                if (Math.abs(left.midMs() - right.midMs()) < WINDOW_MS
                        && TextSimilarity.jaccard(left.text(), right.text()) >= REDUNDANCY_SIMILARITY) {
                    duplicated++;
                }
            }
        }
        return pairs == 0 ? 0.0 : (double) duplicated / pairs;
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }
}
