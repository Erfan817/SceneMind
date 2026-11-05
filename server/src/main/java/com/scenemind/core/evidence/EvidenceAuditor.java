package com.scenemind.core.evidence;

import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import com.scenemind.core.ranking.TextSimilarity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 结构化证据校验内核（纯函数、零框架依赖）。
 *
 * <p>相对于「返回 boolean 的连续子串包含」，这里做了三件事：</p>
 * <ol>
 *   <li><b>拒绝原因可枚举</b>：让 AgentLoop 能按原因决定「定向补检索」还是「判定幻觉直接终止」，
 *       而不是所有失败都无差别重跑两轮，白烧 Token。</li>
 *   <li><b>通道隔离</b>：声明 {@code ASR+OCR} 时，分别检查两条通道，要求证据在<em>同一条通道内</em>
 *       完整出现。改造前把 ASR 与 OCR 文本直接拼接，模型从 ASR 尾巴 + OCR 开头拼出的一句
 *       「原文里并不存在」的话也会被判定为有证据 —— 这是一类真实的假阳性。</li>
 *   <li><b>诊断相似度</b>：拒绝时仍给出证据文本被原文二元组覆盖的比例，用于区分
 *       「模型只是改写了几个字」和「模型完全编造」，为后续放宽策略提供数据。</li>
 * </ol>
 *
 * <p>通过条件保持与改造前一致（归一化后连续子串命中），确保既有行为与回归测试不被破坏。</p>
 */
public final class EvidenceAuditor {

    private static final Set<String> ALLOWED_SOURCES = Set.of("ASR", "OCR", "ASR+OCR");

    public AuditResult audit(EvidenceCorpus corpus, EvidenceClaim claim) {
        if (corpus == null || claim == null) {
            return AuditResult.rejected(RejectionReason.NULL_INPUT, 0.0, "上下文或证据为空");
        }
        if (corpus.segments().isEmpty()) {
            return AuditResult.rejected(RejectionReason.NO_CONTEXT, 0.0, "视频上下文没有任何片段");
        }
        String needle = normalize(claim.content());
        if (needle.isEmpty()) {
            return AuditResult.rejected(RejectionReason.EMPTY_CONTENT, 0.0, "证据原文为空");
        }

        String source = claim.source() == null ? "" : claim.source().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SOURCES.contains(source)) {
            return AuditResult.rejected(RejectionReason.UNKNOWN_SOURCE, 0.0,
                    "不支持的来源: " + claim.source());
        }

        List<EvidenceSegment> covering = corpus.segments().stream()
                .filter(segment -> segment.covers(claim.timestampMs()))
                .toList();
        if (covering.isEmpty()) {
            return AuditResult.rejected(RejectionReason.TIMESTAMP_OUT_OF_RANGE, 0.0,
                    "时间戳 " + claim.timestampMs() + "ms 未落在任何片段区间");
        }

        boolean anyChannelText = false;
        double bestSimilarity = 0.0;
        String bestChannel = "";
        for (EvidenceSegment segment : covering) {
            for (ChannelText channelText : channelTexts(segment, source)) {
                String haystack = normalize(channelText.text());
                if (haystack.isEmpty()) continue;
                anyChannelText = true;
                double similarity = coverage(needle, haystack);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestChannel = channelText.channel();
                }
                // 严格语义：归一化后的连续子串命中才算通过。
                if (haystack.contains(needle)) {
                    return AuditResult.accepted(channelText.channel(),
                            "证据在 " + channelText.channel() + " 通道内可逐字核验");
                }
            }
        }

        if (!anyChannelText) {
            return AuditResult.rejected(RejectionReason.CHANNEL_EMPTY, 0.0,
                    "声明通道在该时间点没有可用文本");
        }
        if (crossChannelSpliceHits(covering, needle)) {
            return AuditResult.rejected(RejectionReason.CROSS_CHANNEL_SPLICE, bestSimilarity,
                    "证据只能由 ASR 与 OCR 拼接得到，拒绝作为有效证据");
        }
        return AuditResult.rejected(RejectionReason.TEXT_NOT_FOUND, bestSimilarity,
                "证据文本未命中，最高覆盖率 " + String.format(Locale.ROOT, "%.2f", bestSimilarity)
                        + "（" + bestChannel + "）");
    }

    public boolean timestampCovered(EvidenceCorpus corpus, EvidenceClaim claim) {
        return corpus != null && claim != null && corpus.segments().stream()
                .anyMatch(segment -> segment.covers(claim.timestampMs()));
    }

    public boolean supported(EvidenceCorpus corpus, EvidenceClaim claim) {
        return audit(corpus, claim).accepted();
    }

    public boolean supportsClaim(EvidenceCorpus corpus, String claimText, EvidenceClaim evidence) {
        if (evidence == null || claimText == null) return false;
        String expected = normalize(claimText);
        return !expected.isEmpty()
                && expected.equals(normalize(evidence.claim()))
                && supported(corpus, evidence);
    }

    /** 按声明来源拆出互不混用的通道文本。 */
    private List<ChannelText> channelTexts(EvidenceSegment segment, String source) {
        List<ChannelText> result = new ArrayList<>();
        if (source.equals("ASR") || source.equals("ASR+OCR")) {
            result.add(new ChannelText("ASR", segment.transcript()));
        }
        if (source.equals("OCR") || source.equals("ASR+OCR")) {
            result.add(new ChannelText("OCR", segment.joinedOcr()));
        }
        return result;
    }

    /** 复现改造前的拼接行为，用于识别「只在拼接串里成立」的假阳性。 */
    private boolean crossChannelSpliceHits(List<EvidenceSegment> covering, String needle) {
        for (EvidenceSegment segment : covering) {
            String spliced = normalize(segment.transcript() + " " + segment.joinedOcr());
            if (spliced.contains(needle)) return true;
        }
        return false;
    }

    /** 证据二元组被原文覆盖的比例，仅作诊断，不用于放宽通过条件。 */
    private double coverage(String needle, String haystack) {
        Set<String> needleGrams = TextSimilarity.bigrams(needle);
        Set<String> haystackGrams = TextSimilarity.bigrams(haystack);
        if (needleGrams.isEmpty() || haystackGrams.isEmpty()) return 0.0;
        int hit = 0;
        for (String gram : needleGrams) {
            if (haystackGrams.contains(gram)) hit++;
        }
        return (double) hit / needleGrams.size();
    }

    /** 归一化：全角转半角、只保留字母数字、统一大小写。 */
    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch >= 0xFF01 && ch <= 0xFF5E) ch = (char) (ch - 0xFEE0);
            if (Character.isLetterOrDigit(ch)) out.append(Character.toLowerCase(ch));
        }
        return out.toString();
    }

    /** 去重后的关键词命中率，供离线评测复用（改造前是 Runner 里的 private 方法）。 */
    public static double keywordCoverage(String output, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return 1.0;
        String normalized = normalize(output);
        Set<String> distinct = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()) distinct.add(normalize(keyword));
        }
        if (distinct.isEmpty()) return 1.0;
        long hit = distinct.stream().filter(normalized::contains).count();
        return (double) hit / distinct.size();
    }

    private record ChannelText(String channel, String text) {
    }
}
