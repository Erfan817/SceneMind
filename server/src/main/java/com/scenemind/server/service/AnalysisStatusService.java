package com.scenemind.server.service;

import com.scenemind.server.dto.AgentState;
import com.scenemind.server.dto.AnalysisMode;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.EvidenceAuditReport;
import com.scenemind.server.dto.TaskStage;
import com.scenemind.server.dto.TaskStatus;
import com.scenemind.server.dto.VideoContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalysisStatusService {

    private final AgentCheckpointService checkpointService;
    private final AnalysisDispatchService dispatchService;
    private final EvidenceVerificationService evidenceVerificationService;

    /** 两参构造器保留给既有单测（无需 Spring、无需真实上下文）。 */
    public AnalysisStatusService(AgentCheckpointService checkpointService,
                                 AnalysisDispatchService dispatchService) {
        this(checkpointService, dispatchService, new EvidenceVerificationService());
    }

    @Autowired
    public AnalysisStatusService(AgentCheckpointService checkpointService,
                                 AnalysisDispatchService dispatchService,
                                 EvidenceVerificationService evidenceVerificationService) {
        this.checkpointService = checkpointService;
        this.dispatchService = dispatchService;
        this.evidenceVerificationService = evidenceVerificationService;
    }

    public TaskStatus current(Long mediaId, String goal) {
        return current(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStatus current(Long mediaId, String goal, AnalysisMode mode) {
        // The last successful result is retained until the worker applies a revision.
        // It must not terminate the new revision's SSE stream while that revision is queued.
        if (checkpointService.isRevisionPending(mediaId, goal, mode)
                && dispatchService.isActive(mediaId, goal, mode)) {
            return TaskStatus.of(TaskStatus.State.QUEUED, "修订任务已排队，等待按新计划执行");
        }
        AgentState result = checkpointService.loadResult(mediaId, goal, mode);
        if (result != null && result.result() != null) {
            // 结果复用/重新打开面板时同样带上音画冲突与证据审计，而不只在实时 SSE 里出现一次。
            EvidenceAuditReport audit = evidenceAudit(mediaId, result.result());
            return TaskStatus.completed(result,
                    audit == null ? List.of() : audit.conflicts(), audit);
        }

        TaskStage stage = checkpointService.loadStage(mediaId, goal, mode);
        if (dispatchService.isActive(mediaId, goal, mode)) {
            TaskStatus.State state = stage == null ? TaskStatus.State.QUEUED : TaskStatus.State.PROCESSING;
            return TaskStatus.of(state, statusMessage(stage));
        }
        if (stage == TaskStage.BUDGET_EXHAUSTED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "Agent 已达到本次任务预算，请调整目标后重试");
        }
        if (stage == TaskStage.FAILED || stage == TaskStage.DEAD_LETTERED) {
            return TaskStatus.of(TaskStatus.State.FAILED, "分析失败，请稍后重试");
        }
        return TaskStatus.of(TaskStatus.State.NOT_STARTED, "尚未提交分析任务");
    }

    public TaskStage stage(Long mediaId, String goal) {
        return stage(mediaId, goal, AnalysisMode.GENERAL);
    }

    public TaskStage stage(Long mediaId, String goal, AnalysisMode mode) {
        return checkpointService.loadStage(mediaId, goal, mode);
    }

    /** 审计是增强信号：上下文读取失败时降级为 null，不能拖垮状态查询。 */
    private EvidenceAuditReport evidenceAudit(Long mediaId, AnalysisResult result) {
        try {
            VideoContext context = checkpointService.loadContext(mediaId);
            if (context == null) return null;
            return evidenceVerificationService.auditResult(context, result);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String statusMessage(TaskStage stage) {
        if (stage == null || stage == TaskStage.QUEUED) return "任务已排队";
        return switch (stage) {
            case VIDEO_CONTEXT, CONTEXT_COMPLETED -> "正在解析视频语音和关键画面";
            case CHUNKS_COMPLETED -> "正在检索与目标相关的视频证据";
            case PLAN_COMPLETED -> "Planner 已完成任务拆解";
            case EXECUTOR_STARTED, EXECUTOR_COMPLETED -> "Executor 正在生成结构化产物";
            case CRITIC_STARTED -> "Critic 正在核验结论和证据";
            case CRITIC_RETRY_REQUIRED, EVIDENCE_REFRESHED -> "正在根据 Critic 反馈补充证据";
            case RETRYING -> "任务执行异常，正在自动重试";
            default -> "正在分析视频";
        };
    }
}
