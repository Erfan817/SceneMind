package com.scenemind.server.service;

import com.scenemind.server.dto.EvidenceAuditReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把结构化拒绝原因翻译成 AgentLoop 的重试策略（纯函数、零框架依赖，可单独单测）。
 *
 * <p>改造前所有校验失败都无差别重跑一轮检索：模型编造来源、把 ASR 尾巴和 OCR 开头
 * 拼成一句原文里不存在的话，也会被当成「证据不够」再烧一次 Token。这里按
 * {@code retryable} 分流：</p>
 * <ul>
 *   <li>{@code TIMESTAMP_OUT_OF_RANGE / CHANNEL_EMPTY / TEXT_NOT_FOUND}：证据缺口可修，
 *       收集时间戳交给 Critic 定向补检索；</li>
 *   <li>{@code UNKNOWN_SOURCE / CROSS_CHANNEL_SPLICE}：疑似幻觉，不进补检索名单；
 *       若一轮里只剩这类拒绝，则只重写不再补检索。</li>
 * </ul>
 */
public final class EvidenceRetryPolicy {

    public enum Action { NONE, REFRESH_EVIDENCE, REWRITE_ONLY }

    public record Decision(
            Action action,
            List<Long> retryTimestamps,
            List<String> feedback,
            String reason
    ) {
        public Decision {
            retryTimestamps = retryTimestamps == null ? List.of() : List.copyOf(retryTimestamps);
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
        }
    }

    private EvidenceRetryPolicy() {
    }

    public static Decision decide(EvidenceAuditReport report) {
        if (report == null || report.findings().isEmpty()) {
            return new Decision(Action.NONE, List.of(), List.of(), "无证据校验结果");
        }
        List<String> feedback = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        for (EvidenceAuditReport.EvidenceFinding finding : report.findings()) {
            if (finding.accepted()) continue;
            if (finding.retryable()) {
                feedback.add("[" + finding.reason() + "] 证据无法核验，需定向补检索："
                        + finding.timestampMs() + "ms");
                if (!timestamps.contains(finding.timestampMs())) {
                    timestamps.add(finding.timestampMs());
                }
            } else {
                feedback.add("[" + finding.reason() + "] 疑似幻觉，不再补检索（命中通道 "
                        + finding.channel() + "）");
            }
        }
        if (!timestamps.isEmpty()) {
            return new Decision(Action.REFRESH_EVIDENCE, timestamps, feedback,
                    "存在可修的证据缺口，按时间戳定向补检索；疑似幻觉项不重试");
        }
        if (report.hasHallucination()) {
            return new Decision(Action.REWRITE_ONLY, List.of(), feedback,
                    "仅剩疑似幻觉（伪造来源 / 跨通道拼接），直接判幻觉，不再补检索");
        }
        return new Decision(Action.NONE, List.of(), feedback, "证据均通过校验");
    }

    /** 写进 trace 的 diagnostics.retry，供前端调试面板与排障使用。 */
    public static Map<String, Object> toMap(Decision decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", decision.action().name());
        map.put("retryTimestamps", decision.retryTimestamps());
        map.put("reason", decision.reason());
        map.put("feedback", decision.feedback());
        return map;
    }
}
