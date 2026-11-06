package com.scenemind.core.eval;

import com.scenemind.core.evidence.AuditResult;
import com.scenemind.core.evidence.EvidenceAuditor;
import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;

import java.util.List;
import java.util.Objects;

/**
 * 引用质量指标：把「每条结论都能回到原始时间戳」从文档承诺变成可计算的数字。
 *
 * <p>三个指标与 Video RAG 评测里的 citation attribution 对齐：</p>
 * <ul>
 *   <li><b>CiteP</b>：被引用证据中通过 {@link EvidenceAuditor} 逐字核验的比例，
 *       衡量「引用是否真实存在」；</li>
 *   <li><b>CiteR</b>：结论中至少有一条有效引用支撑的比例，衡量「结论是否都有出处」；</li>
 *   <li><b>CiteF1</b>：二者的调和平均。</li>
 * </ul>
 *
 * <p>这是纯函数，不依赖 Spring / 数据库 / 模型，可直接在离线评测里复现。</p>
 */
public record CitationMetrics(
        double precision,
        double recall,
        double f1,
        int evidenceTotal,
        int evidenceSupported,
        int claimTotal,
        int claimSupported
) {

    public CitationMetrics {
        if (precision < 0 || precision > 1) throw new IllegalArgumentException("precision must be within [0,1]");
        if (recall < 0 || recall > 1) throw new IllegalArgumentException("recall must be within [0,1]");
        if (f1 < 0 || f1 > 1) throw new IllegalArgumentException("f1 must be within [0,1]");
    }

    /** 一条待核验的结论及其候选引用。 */
    public record Claim(String id, String text, List<Citation> citations) {
        public Claim {
            Objects.requireNonNull(id, "id");
            text = text == null ? "" : text.trim();
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /** 一条引用：时间戳 + 来源 + 原文。 */
    public record Citation(long timestampMs, String source, String content) {
        public Citation {
            if (timestampMs < 0) throw new IllegalArgumentException("citation timestamp cannot be negative");
            source = source == null ? "" : source.trim();
            content = content == null ? "" : content.trim();
        }
    }

    /**
     * 计算引用质量。没有引用时 precision=0，没有结论时 recall=0；
     * 这样「没有产出证据」不会被误报成满分。
     */
    public static CitationMetrics evaluate(EvidenceCorpus corpus, List<Claim> claims) {
        EvidenceAuditor auditor = new EvidenceAuditor();
        List<Claim> safeClaims = claims == null ? List.of() : claims;

        int evidenceTotal = 0;
        int evidenceSupported = 0;
        int claimTotal = 0;
        int claimSupported = 0;

        for (Claim claim : safeClaims) {
            if (claim == null) continue;
            claimTotal++;
            boolean supportedClaim = false;
            for (Citation citation : claim.citations()) {
                evidenceTotal++;
                EvidenceClaim evidence = new EvidenceClaim(
                        citation.timestampMs(), citation.source(), citation.content(), claim.text());
                AuditResult audit = auditor.audit(corpus, evidence);
                if (!audit.accepted()) continue;
                evidenceSupported++;
                if (auditor.supportsClaim(corpus, claim.text(), evidence)) {
                    supportedClaim = true;
                }
            }
            if (supportedClaim) claimSupported++;
        }

        double precision = evidenceTotal == 0 ? 0.0 : (double) evidenceSupported / evidenceTotal;
        double recall = claimTotal == 0 ? 0.0 : (double) claimSupported / claimTotal;
        return new CitationMetrics(precision, recall, f1(precision, recall),
                evidenceTotal, evidenceSupported, claimTotal, claimSupported);
    }

    /** 调和平均；任一为 0 时返回 0，避免出现「precision=0 但 F1 很高」的假象。 */
    public static double f1(double precision, double recall) {
        if (precision <= 0 || recall <= 0) return 0.0;
        return 2 * precision * recall / (precision + recall);
    }
}
