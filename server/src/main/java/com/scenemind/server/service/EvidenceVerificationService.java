package com.scenemind.server.service;

import com.scenemind.core.evidence.AuditResult;
import com.scenemind.core.evidence.ConflictDetector;
import com.scenemind.core.eval.CitationMetrics;
import com.scenemind.core.evidence.EvidenceAuditor;
import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.EvidenceAuditReport;
import com.scenemind.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据校验的 Spring 适配层。
 *
 * <p><b>SceneMind 改造：</b>真正的校验规则搬到 {@code com.scenemind.core.evidence}，
 * 这里只做 DTO 转换，保持原有三个 public 方法的语义完全不变（既有回归测试可直接复用）。
 * 同时新增 {@link #audit} 与 {@link #detectConflicts}，把「为什么拒绝」和「音画是否冲突」
 * 暴露给上层做针对性重试，而不是所有失败都无差别重跑 AgentLoop。</p>
 */
@Service
public class EvidenceVerificationService {

    private final EvidenceAuditor auditor = new EvidenceAuditor();
    private final ConflictDetector conflictDetector = new ConflictDetector();

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        return auditor.timestampCovered(toCorpus(context), toClaim(evidence));
    }

    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        return auditor.supported(toCorpus(context), toClaim(evidence));
    }

    public boolean supportsClaim(VideoContext context,
                                 String claim,
                                 AnalysisResult.Evidence evidence) {
        return auditor.supportsClaim(toCorpus(context), claim, toClaim(evidence));
    }

    /** 结构化校验结果：包含拒绝原因、命中通道与文本覆盖度。 */
    public AuditResult audit(VideoContext context, AnalysisResult.Evidence evidence) {
        return auditor.audit(toCorpus(context), toClaim(evidence));
    }

    /** 检测同一时间点语音与画面是否给出互相矛盾的日期。 */
    public ConflictDetector.ConflictReport detectConflicts(VideoContext context) {
        return conflictDetector.detect(toCorpus(context));
    }

    /**
     * 一次分析任务的聚合核验报告：逐条证据的结构化判定 + 音画冲突。
     *
     * <p>这是把 {@code core.evidence} 的新能力真正接进运行时的入口：AgentLoop 用它
     * 按 {@code retryable} 分流，{@code AnalysisStatusService} 用它给结果加冲突标注，
     * 埋点用它输出可解释诊断。旧的三个 boolean 方法保持语义不变。</p>
     */
    public EvidenceAuditReport auditResult(VideoContext context, AnalysisResult result) {
        List<EvidenceAuditReport.EvidenceFinding> findings = new ArrayList<>();
        if (result != null && result.evidence() != null) {
            for (AnalysisResult.Evidence evidence : result.evidence()) {
                AuditResult audit = audit(context, evidence);
                findings.add(new EvidenceAuditReport.EvidenceFinding(
                        evidence.timestampMs(), audit.accepted(), audit.reason().name(),
                        audit.retryable(), audit.similarity(), audit.channel()));
            }
        }
        List<EvidenceAuditReport.ConflictNotice> conflicts = detectConflicts(context).conflicts().stream()
                .map(conflict -> new EvidenceAuditReport.ConflictNotice(
                        conflict.startMs(), conflict.asrValue(), conflict.ocrValue(), conflict.detail()))
                .toList();
        int accepted = (int) findings.stream()
                .filter(EvidenceAuditReport.EvidenceFinding::accepted).count();
        int retryable = (int) findings.stream()
                .filter(f -> !f.accepted() && f.retryable()).count();
        int hallucination = (int) findings.stream()
                .filter(f -> !f.accepted() && !f.retryable()).count();
        List<String> notes = new ArrayList<>();
        if (retryable > 0) notes.add(retryable + " 条证据缺口可定向补检索");
        if (hallucination > 0) notes.add(hallucination + " 条证据疑似幻觉，不再补检索");
        if (!conflicts.isEmpty()) {
            notes.add("检测到 " + conflicts.size() + " 处音画冲突，结论须显式标注不确定性");
        }
        return new EvidenceAuditReport(findings.size(), accepted, retryable, hallucination,
                findings, conflicts, notes);
    }

    /**
     * 结构化 claim-evidence 的引用质量：只在模型给出 {@code claims[] + evidenceIds[]} 时启用；
     * 旧结果返回 {@code null}，由调用方回退到 conclusions/evidence.claim 的聚合指标。
     */
    public CitationMetrics citationMetrics(VideoContext context, AnalysisResult result) {
        if (result == null || result.claims() == null || result.claims().isEmpty()) return null;
        if (result.claims().stream().allMatch(claim -> claim.evidenceIds().isEmpty())) return null;
        Map<String, AnalysisResult.Evidence> evidenceById = new LinkedHashMap<>();
        for (AnalysisResult.Evidence evidence : result.evidence()) {
            if (!evidence.id().isBlank()) evidenceById.putIfAbsent(evidence.id(), evidence);
        }
        List<CitationMetrics.Claim> claims = result.claims().stream()
                .map(claim -> new CitationMetrics.Claim(
                        claim.id(),
                        claim.text(),
                        claim.evidenceIds().stream()
                                .map(evidenceById::get)
                                .filter(evidence -> evidence != null)
                                .map(evidence -> new CitationMetrics.Citation(
                                        evidence.timestampMs(), evidence.source(), evidence.content()))
                                .toList()))
                .toList();
        return CitationMetrics.evaluate(toCorpus(context), claims);
    }

    private static EvidenceCorpus toCorpus(VideoContext context) {
        if (context == null || context.segments() == null) return new EvidenceCorpus(List.of());
        List<EvidenceSegment> segments = context.segments().stream()
                .map(segment -> new EvidenceSegment(segment.startMs(), segment.endMs(),
                        segment.transcript(), segment.ocrTexts()))
                .toList();
        return new EvidenceCorpus(segments);
    }

    private static EvidenceClaim toClaim(AnalysisResult.Evidence evidence) {
        if (evidence == null) return null;
        return new EvidenceClaim(evidence.timestampMs(), evidence.source(),
                evidence.content(), evidence.claim());
    }
}
