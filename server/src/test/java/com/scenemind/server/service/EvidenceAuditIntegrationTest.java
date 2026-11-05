package com.scenemind.server.service;

import com.scenemind.server.dto.AgentState;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.EvidenceAuditReport;
import com.scenemind.server.dto.TaskStatus;
import com.scenemind.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据核验从内核到运行时的贯通测试：聚合报告、冲突标注、结果 markdown。
 */
class EvidenceAuditIntegrationTest {

    private static VideoContext conflictContext() {
        return new VideoContext("offline://conflicting-deadline", "核验上线日期",
                List.of(new VideoContext.VideoSegment(60_000, 120_000,
                        "我们计划八月一日正式上线。",
                        List.of("正式上线：8月3日"), List.of())));
    }

    private static AnalysisResult resultWith(String source, String content) {
        return new AnalysisResult("上线日期核验",
                List.of("语音与画面给出了不同的上线日期"),
                List.of(new AnalysisResult.Evidence(75_000, source, content, "最终上线日期")),
                List.of(), List.of());
    }

    @Test
    void auditReportAggregatesRejectionsAndConflicts() {
        EvidenceVerificationService service = new EvidenceVerificationService();
        EvidenceAuditReport report = service.auditResult(conflictContext(),
                resultWith("ASR+OCR", "上线。正式上线"));

        assertEquals(1, report.total());
        assertEquals(0, report.accepted());
        assertEquals(0, report.retryableRejections());
        assertEquals(1, report.hallucinationRejections(),
                "跨通道拼接应被归为不可重试的疑似幻觉");
        assertTrue(report.hasConflicts());
        assertEquals(1, report.conflicts().size());
        assertTrue(report.conflicts().get(0).asrValue().contains("8月1日"));
        assertTrue(report.conflicts().get(0).ocrValue().contains("8月3日"));
        assertTrue(report.toMap().containsKey("findings"));
    }

    @Test
    void acceptedEvidenceProducesNoHallucinationOrConflicts() {
        EvidenceVerificationService service = new EvidenceVerificationService();
        VideoContext clean = new VideoContext("offline://clean", "核验结论",
                List.of(new VideoContext.VideoSegment(60_000, 120_000,
                        "前序遍历先访问根节点，然后递归访问左子树。", List.of(), List.of())));
        EvidenceAuditReport report = service.auditResult(clean,
                resultWith("ASR", "前序遍历先访问根节点"));

        assertEquals(1, report.accepted());
        assertEquals(0, report.hallucinationRejections());
        assertFalse(report.hasConflicts());
        assertEquals(EvidenceRetryPolicy.Action.NONE, EvidenceRetryPolicy.decide(report).action());
    }

    @Test
    void completedStatusCarriesConflictWarningsIntoMarkdown() {
        EvidenceVerificationService service = new EvidenceVerificationService();
        VideoContext context = conflictContext();
        AnalysisResult result = resultWith("ASR+OCR", "上线。正式上线");
        EvidenceAuditReport report = service.auditResult(context, result);
        AgentState state = new AgentState("核验上线日期", null, result,
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);

        TaskStatus status = TaskStatus.completed(state, report.conflicts());

        assertEquals(TaskStatus.State.COMPLETED, status.state());
        assertEquals(1, status.conflicts().size());
        assertTrue(status.result().contains("音画冲突提示"), "结果 markdown 必须显式标注冲突");
        assertTrue(status.result().contains("[01:00]"), "冲突条目要带可回放的时间戳");
        assertTrue(status.result().contains("8月1日"));
        assertTrue(status.message().contains("冲突"));
    }

    @Test
    void completedStatusWithoutConflictsStaysBackwardCompatible() {
        AnalysisResult result = new AnalysisResult("标题", List.of("结论"),
                List.of(new AnalysisResult.Evidence(0, "ASR", "原文", "结论")),
                List.of(), List.of());
        AgentState state = new AgentState("goal", null, result,
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()), 1);

        TaskStatus status = TaskStatus.completed(state);

        assertEquals("任务完成", status.message());
        assertTrue(status.conflicts().isEmpty());
        assertFalse(status.result().contains("音画冲突提示"));
        assertEquals(TaskStatus.completed(result.toMarkdown()).result(), status.result());
    }
}
