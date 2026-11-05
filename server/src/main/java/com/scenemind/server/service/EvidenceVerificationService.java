package com.scenemind.server.service;

import com.scenemind.core.evidence.AuditResult;
import com.scenemind.core.evidence.ConflictDetector;
import com.scenemind.core.evidence.EvidenceAuditor;
import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.List;

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
