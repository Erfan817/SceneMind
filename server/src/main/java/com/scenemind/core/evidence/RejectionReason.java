package com.scenemind.core.evidence;

/**
 * 证据被拒绝的结构化原因。
 *
 * <p>改造前的校验只返回 boolean，线上出现「Critic 反复重检索但始终不通过」时
 * 无法区分是时间戳越界、模型改写了原文，还是模型编造了来源。把原因枚举化之后，
 * 才能做针对性重试（时间戳问题回到检索、措辞问题放宽归一、来源问题直接拒绝）。</p>
 */
public enum RejectionReason {
    NONE("通过"),
    NULL_INPUT("输入为空"),
    NO_CONTEXT("视频上下文为空"),
    EMPTY_CONTENT("证据文本为空"),
    UNKNOWN_SOURCE("声明了未支持的证据来源"),
    TIMESTAMP_OUT_OF_RANGE("时间戳未落在任何片段区间内"),
    CHANNEL_EMPTY("声明通道在该时间点没有可用文本"),
    CROSS_CHANNEL_SPLICE("证据只能由 ASR 与 OCR 跨通道拼接得到，判定为假阳性"),
    TEXT_NOT_FOUND("证据文本在声明通道内无法核验");

    private final String description;

    RejectionReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** 是否值得让 Agent 重新检索（而非直接判定模型幻觉）。 */
    public boolean retryable() {
        return switch (this) {
            case TIMESTAMP_OUT_OF_RANGE, CHANNEL_EMPTY, TEXT_NOT_FOUND -> true;
            default -> false;
        };
    }
}
