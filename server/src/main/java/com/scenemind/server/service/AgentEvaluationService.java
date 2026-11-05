package com.scenemind.server.service;

import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.TextSimilarity;
import com.scenemind.server.dto.AgentFeedback;
import com.scenemind.server.dto.AgentState;
import com.scenemind.server.dto.AnalysisMode;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.EvidenceAuditReport;
import com.scenemind.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentEvaluationService {

    private final AgentCheckpointService checkpointService;
    private final EvidenceVerificationService evidenceVerificationService;
    private final RankingConfig rankingConfig;

    public AgentEvaluationService(AgentCheckpointService checkpointService,
                                  EvidenceVerificationService evidenceVerificationService,
                                  RankingConfig rankingConfig) {
        this.checkpointService = checkpointService;
        this.evidenceVerificationService = evidenceVerificationService;
        this.rankingConfig = rankingConfig;
    }

    public Map<String, Object> evaluate(Long mediaId, String goal) {
        return evaluate(mediaId, goal, AnalysisMode.GENERAL);
    }

    public Map<String, Object> evaluate(Long mediaId, String goal, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        VideoContext context = checkpointService.loadContext(mediaId);
        AgentState state = checkpointService.loadResult(mediaId, goal, resolvedMode);
        if (state == null) state = checkpointService.loadCriticState(mediaId, goal, resolvedMode);

        List<AgentFeedback> feedback = checkpointService.loadFeedback(mediaId).stream()
                .filter(item -> goal.equals(item.goal()) || goal.equals(item.correctedGoal()))
                .filter(item -> AnalysisMode.fromNullable(item.mode()) == resolvedMode)
                .toList();

        Map<String, Object> metrics = new LinkedHashMap<>(evaluate(context, state));
        metrics.put("userAcceptanceRate", userAcceptanceRate(feedback));
        metrics.put("feedbackSamples", feedback.size());
        return metrics;
    }

    public Map<String, Object> evaluate(VideoContext context, AgentState state) {
        AnalysisResult result = state == null ? null : state.result();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("structuredValid", structuredValid(result));
        metrics.put("timestampCoverageRate", timestampCoverageRate(context, result));
        metrics.put("evidenceSupportRate", evidenceSupportRate(context, result));
        metrics.put("claimEvidenceSupportRate", claimEvidenceSupportRate(context, result));
        metrics.put("criticPassed", state != null && state.critique() != null && state.critique().passed());
        // SceneMind 新增：证据质量不再只看「有没有时间戳」，还看「是不是一堆重复片段」
        // 以及「结论是否覆盖了视频的不同时段」。
        metrics.put("evidenceRedundancyRate", evidenceRedundancyRate(result));
        metrics.put("temporalSpread", temporalSpread(result));
        // 结构化证据审计：MCP 的 verify_evidence 与前端调试面板都消费这份数据，
        // 而不是各自重新实现一套伪校验。
        EvidenceAuditReport audit = evidenceVerificationService.auditResult(context, result);
        metrics.put("evidenceAudit", audit.toMap());
        metrics.put("conflictFlagged", audit.hasConflicts());
        return metrics;
    }

    private double evidenceRedundancyRate(AnalysisResult result) {
        if (result == null || result.evidence() == null || result.evidence().size() < 2) return 0;
        List<AnalysisResult.Evidence> evidence = result.evidence();
        int pairs = 0;
        int duplicated = 0;
        for (int i = 0; i < evidence.size(); i++) {
            for (int j = i + 1; j < evidence.size(); j++) {
                pairs++;
                if (TextSimilarity.jaccard(evidence.get(i).content(), evidence.get(j).content())
                        >= rankingConfig.redundancySimilarity()) {
                    duplicated++;
                }
            }
        }
        return pairs == 0 ? 0 : (double) duplicated / pairs;
    }

    private double temporalSpread(AnalysisResult result) {
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) return 0;
        Set<Long> windows = new HashSet<>();
        for (AnalysisResult.Evidence evidence : result.evidence()) {
            windows.add(evidence.timestampMs() / rankingConfig.windowMs());
        }
        return (double) windows.size() / result.evidence().size();
    }

    private boolean structuredValid(AnalysisResult result) {
        return result != null
                && result.title() != null && !result.title().isBlank()
                && result.conclusions() != null && !result.conclusions().isEmpty()
                && result.evidence() != null && !result.evidence().isEmpty();
    }

    private double evidenceSupportRate(VideoContext context, AnalysisResult result) {
        if (context == null || result == null || result.evidence() == null || result.evidence().isEmpty()) return 0;
        long supported = result.evidence().stream()
                .filter(evidence -> evidenceVerificationService.supported(context, evidence))
                .count();
        return (double) supported / result.evidence().size();
    }

    private double timestampCoverageRate(VideoContext context, AnalysisResult result) {
        if (context == null || result == null || result.evidence().isEmpty()) return 0;
        long covered = result.evidence().stream()
                .filter(evidence -> evidenceVerificationService.timestampCovered(context, evidence))
                .count();
        return (double) covered / result.evidence().size();
    }

    private double claimEvidenceSupportRate(VideoContext context, AnalysisResult result) {
        if (context == null || result == null || result.conclusions().isEmpty()) return 0;
        long supported = result.conclusions().stream()
                .filter(claim -> result.evidence().stream().anyMatch(
                        evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)))
                .count();
        return (double) supported / result.conclusions().size();
    }

    private double userAcceptanceRate(List<AgentFeedback> feedback) {
        List<AgentFeedback> rated = feedback.stream().filter(item -> item.rating() != null).toList();
        if (rated.isEmpty()) return 0;
        long accepted = rated.stream().filter(item -> item.rating() > 0).count();
        return (double) accepted / rated.size();
    }
}
