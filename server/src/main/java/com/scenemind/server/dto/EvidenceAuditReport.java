package com.scenemind.server.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次分析任务的证据核验报告：把 {@code core.evidence} 的结构化拒绝原因与
 * 音画冲突信号汇总成运行时的一等输出，供 Critic 分流、状态/SSE 接口与前端展示消费。
 *
 * <p>这是「适配层」DTO，不属于零依赖内核：内核只产出 {@code AuditResult} 与
 * {@code ConflictReport}，由 {@code EvidenceVerificationService} 转换到这里。</p>
 */
public record EvidenceAuditReport(
        int total,
        int accepted,
        int retryableRejections,
        int hallucinationRejections,
        List<EvidenceFinding> findings,
        List<ConflictNotice> conflicts,
        List<String> notes
) {
    public EvidenceAuditReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 单条证据的校验结论。 */
    public record EvidenceFinding(
            long timestampMs,
            boolean accepted,
            String reason,
            boolean retryable,
            double similarity,
            String channel
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestampMs", timestampMs);
            map.put("accepted", accepted);
            map.put("reason", reason);
            map.put("retryable", retryable);
            map.put("similarity", similarity);
            map.put("channel", channel);
            return map;
        }
    }

    /** 一处语音/画面互相矛盾的时间点。 */
    public record ConflictNotice(
            long startMs,
            String asrValue,
            String ocrValue,
            String detail
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startMs", startMs);
            map.put("asrValue", asrValue);
            map.put("ocrValue", ocrValue);
            map.put("detail", detail);
            return map;
        }
    }

    public boolean hasHallucination() {
        return hallucinationRejections > 0;
    }

    public boolean hasRetryableRejections() {
        return retryableRejections > 0;
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /** 供埋点 / trace 序列化，避免把 Java 枚举与 record 直接塞进 Redis JSON。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("accepted", accepted);
        map.put("retryableRejections", retryableRejections);
        map.put("hallucinationRejections", hallucinationRejections);
        map.put("findings", findings.stream().map(EvidenceFinding::toMap).toList());
        map.put("conflicts", conflictMaps());
        map.put("notes", notes);
        return map;
    }

    public List<Map<String, Object>> conflictMaps() {
        return conflicts.stream().map(ConflictNotice::toMap).toList();
    }
}
