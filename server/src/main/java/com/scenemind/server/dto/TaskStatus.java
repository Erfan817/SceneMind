package com.scenemind.server.dto;

import java.util.List;

public record TaskStatus(State state, String result, String message,
                         List<EvidenceAuditReport.ConflictNotice> conflicts,
                         EvidenceAuditReport evidenceAudit) {

    public enum State {
        NOT_STARTED,
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public TaskStatus {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /** 兼容旧调用方与旧 SSE 载荷：没有冲突/审计信号时等价于原来的三字段结构。 */
    public TaskStatus(State state, String result, String message) {
        this(state, result, message, List.of(), null);
    }

    public TaskStatus(State state, String result, String message,
                      List<EvidenceAuditReport.ConflictNotice> conflicts) {
        this(state, result, message, conflicts, null);
    }

    public static TaskStatus of(State state, String message) {
        return new TaskStatus(state, null, message, List.of(), null);
    }

    public static TaskStatus completed(String result) {
        return new TaskStatus(State.COMPLETED, result, "任务完成", List.of(), null);
    }

    public static TaskStatus completed(AgentState agentState) {
        return completed(agentState, List.of(), null);
    }

    public static TaskStatus completed(AgentState agentState,
                                       List<EvidenceAuditReport.ConflictNotice> conflicts) {
        return completed(agentState, conflicts, null);
    }

    /**
     * 把音画冲突与证据审计显式写进结果 markdown 与结构化字段：
     * 「画面与语音冲突时标注不确定性，而不是替用户猜」必须是用户看得见的输出，
     * 而不只是埋点里的一个布尔值。
     */
    public static TaskStatus completed(AgentState agentState,
                                       List<EvidenceAuditReport.ConflictNotice> conflicts,
                                       EvidenceAuditReport evidenceAudit) {
        String markdown = agentState.result().toMarkdown();
        boolean criticFailed = agentState.critique() != null && !agentState.critique().passed();
        List<EvidenceAuditReport.ConflictNotice> safeConflicts =
                conflicts == null || conflicts.isEmpty()
                        ? (evidenceAudit == null ? List.of() : evidenceAudit.conflicts())
                        : conflicts;
        StringBuilder prefix = new StringBuilder();
        String warning = null;
        if (!safeConflicts.isEmpty()) {
            prefix.append("> **音画冲突提示：** 检测到 ")
                    .append(safeConflicts.size())
                    .append(" 处语音与画面信息不一致，结论保留不确定性，请结合原始时间戳人工核验。\n");
            for (EvidenceAuditReport.ConflictNotice conflict : safeConflicts) {
                prefix.append("> - [").append(formatTime(conflict.startMs())).append("] 语音 ")
                        .append(conflict.asrValue()).append(" / 画面 ")
                        .append(conflict.ocrValue()).append('\n');
            }
            prefix.append('\n');
            warning = "检测到音画冲突，结论需结合原始时间戳人工核验。";
        }
        if (criticFailed) {
            warning = "分析已完成，但部分结论未通过 Critic 校验，请结合时间戳证据人工核验。";
        }
        if (warning != null) {
            prefix.append("> **结果提示：** ").append(warning).append("\n\n");
        }
        return new TaskStatus(State.COMPLETED, prefix + markdown,
                warning == null ? "任务完成" : warning, safeConflicts, evidenceAudit);
    }

    private static String formatTime(long timestampMs) {
        long seconds = Math.max(0, timestampMs / 1000);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
