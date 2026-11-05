package com.scenemind.core.evidence;

/**
 * 一次证据校验的完整结论。
 *
 * @param accepted    是否通过
 * @param reason      通过为 {@link RejectionReason#NONE}，否则为具体拒绝原因
 * @param channel     实际命中的通道（ASR / OCR / ASR+OCR），未命中为空
 * @param similarity  [0,1] 证据文本被命中通道文本覆盖的比例；即使拒绝也保留，用于分析模型是改写还是编造
 * @param detail      人类可读说明
 */
public record AuditResult(
        boolean accepted,
        RejectionReason reason,
        String channel,
        double similarity,
        String detail
) {
    public static AuditResult accepted(String channel, String detail) {
        return new AuditResult(true, RejectionReason.NONE, channel, 1.0, detail);
    }

    public static AuditResult rejected(RejectionReason reason, double similarity, String detail) {
        return new AuditResult(false, reason, "", similarity, detail);
    }

    public boolean retryable() {
        return !accepted && reason.retryable();
    }
}
