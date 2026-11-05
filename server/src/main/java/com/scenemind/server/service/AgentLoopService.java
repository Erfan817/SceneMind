package com.scenemind.server.service;

import com.scenemind.server.dto.AgentState;
import com.scenemind.server.dto.AnalysisMode;
import com.scenemind.server.dto.AnalysisResult;
import com.scenemind.server.dto.EvidenceAuditReport;
import com.scenemind.server.dto.TaskStatus;
import com.scenemind.server.dto.TaskStage;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.service.mode.ModeProfile;
import com.scenemind.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 受控 Agent 编排器：恢复状态、执行一轮分析、校验证据，再决定结束还是补跑。 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_PLAN_TASKS = 5;

    private final DeepSeekUtils deepSeekUtils;
    private final LongVideoContextService longVideoContextService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final EvidenceVerificationService evidenceVerificationService;
    private final TaskEventService taskEventService;
    private final int maxRounds;
    private final long maxDurationMs;
    private final long maxEstimatedTokens;
    private final double maxEstimatedCost;
    private final boolean advisoryPassEnabled;

    public AgentLoopService(DeepSeekUtils deepSeekUtils,
                            LongVideoContextService longVideoContextService,
                            AgentCheckpointService checkpointService,
                            AgentTelemetry telemetry,
                            EvidenceVerificationService evidenceVerificationService,
                            TaskEventService taskEventService,
                            @Value("${agent.budget.max-rounds:2}") int maxRounds,
                            @Value("${agent.budget.max-duration-ms:120000}") long maxDurationMs,
                            @Value("${agent.budget.max-estimated-tokens:50000}") long maxEstimatedTokens,
                            @Value("${agent.budget.max-estimated-cost:0}") double maxEstimatedCost,
                            @Value("${agent.critic.advisory-pass:true}") boolean advisoryPassEnabled) {
        this.deepSeekUtils = deepSeekUtils;
        this.longVideoContextService = longVideoContextService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.evidenceVerificationService = evidenceVerificationService;
        this.taskEventService = taskEventService;
        if (maxRounds < 1 || maxDurationMs < 1 || maxEstimatedTokens < 1 || maxEstimatedCost < 0) {
            throw new IllegalArgumentException("Agent 终止预算配置无效");
        }
        this.maxRounds = maxRounds;
        this.maxDurationMs = maxDurationMs;
        this.maxEstimatedTokens = maxEstimatedTokens;
        this.maxEstimatedCost = maxEstimatedCost;
        this.advisoryPassEnabled = advisoryPassEnabled;
    }

    public AgentState run(VideoContext context) {
        return run(null, context, null);
    }

    /** 兼容旧调用方:无模式 = GENERAL(空指令 Profile)。 */
    public AgentState run(Long mediaId, VideoContext context) {
        return run(mediaId, context, null);
    }

    /**
     * 执行一轮受控 Agent 分析。{@code profile} 为空时等价于 GENERAL——三段模式指令均为空串,
     * 拼接后 prompt 与引入模式体系前完全一致,因此默认行为不变。
     */
    public AgentState run(Long mediaId, VideoContext context, ModeProfile profile) {
        validateContext(context);
        return executeWithinBudget(() -> runWithinBudget(mediaId, context, profile));
    }

    /**
     * 让追问检索等 Agent 前置步骤与主循环共享同一个截止时间；嵌套调用会继承更早的 deadline。
     */
    public <T> T executeWithinBudget(Supplier<T> action) {
        try (AgentExecutionBudget.Scope ignored = AgentExecutionBudget.open(maxDurationMs)) {
            return action.get();
        } catch (BudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            AgentExecutionBudget.DeadlineExceededException deadline = findDeadline(e);
            if (deadline == null) throw e;
            telemetry.incrementCurrent("budgetTerminations", 1);
            throw new BudgetExceededException(deadline.getMessage(), e);
        }
    }

    private AgentState runWithinBudget(Long mediaId, VideoContext context, ModeProfile profile) {
        long runStartedNanos = System.nanoTime();
        AgentState savedState = mediaId == null ? null
                : checkpointService.loadCriticState(mediaId, context.userGoal(), modeOf(profile));
        boolean terminalCheckpoint = savedState != null && savedState.result() != null
                && savedState.critique() != null
                && (savedState.round() >= maxRounds || savedState.critique().passed());
        if (terminalCheckpoint
                && isPlanValid(savedState.plan())
                && isResultValid(savedState.result(), profile)) {
            checkpointService.saveResult(mediaId, savedState, modeOf(profile));
            telemetry.incrementCurrent("terminalCheckpointHits", 1);
            return savedState;
        }
        if (terminalCheckpoint) {
            telemetry.incrementCurrent("invalidTerminalCheckpointRepairs", 1);
            savedState = new AgentState(
                    savedState.goal(), savedState.plan(), savedState.result(), savedState.critique(), 0);
        }

        VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
        AgentState.AgentPlan plan = resolvePlan(mediaId, relevantContext, savedState, profile);
        checkBudget(runStartedNanos, "Planner");
        if (mediaId != null) {
            taskEventService.publishAnalysis(mediaId, relevantContext.userGoal(), modeOf(profile),
                    TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 已完成任务拆解"),
                    TaskStage.PLAN_COMPLETED);
        }
        AgentState state = savedState == null
                ? new AgentState(relevantContext.userGoal(), plan, null, null, 0)
                : savedState;
        if (state.critique() != null && !state.critique().passed()) {
            relevantContext = contextForRetry(
                    mediaId, context, relevantContext, state.critique(), profile);
            plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
        }

        // Executor 草稿已经落盘时，MQ 重试直接从 Critic 接着走，避免重复生成整份产物。
        if (state.result() != null && state.critique() == null && state.round() > 0) {
            telemetry.incrementCurrent("criticCheckpointResumes", 1);
            checkBudget(runStartedNanos, "Executor Checkpoint");
            state = critiqueRound(mediaId, relevantContext, plan, state.result(), state.round(), profile);
            if (!state.critique().passed() && state.round() < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }

        for (int round = state.round() + 1; round <= maxRounds; round++) {
            checkBudget(runStartedNanos, "Agent Round " + round);
            state = executeRound(
                    mediaId, relevantContext, plan, state.critique(), round, runStartedNanos, profile);
            if (state.critique().passed()) break;
            if (round < maxRounds) {
                relevantContext = contextForRetry(
                        mediaId, context, relevantContext, state.critique(), profile);
                plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
            }
        }
        validateResult(state.result(), profile);
        if (mediaId != null) checkpointService.saveResult(mediaId, state, modeOf(profile));
        return state;
    }

    private AgentState.AgentPlan resolvePlan(Long mediaId,
                                             VideoContext context,
                                             AgentState savedState,
                                             ModeProfile profile) {
        AgentState.AgentPlan plan = mediaId == null
                ? null
                : checkpointService.loadPlan(mediaId, context.userGoal(), modeOf(profile));
        if (plan == null && savedState != null) plan = savedState.plan();
        boolean shouldPersist = false;
        if (plan == null) {
            plan = deepSeekUtils.plan(context, planInstruction(profile));
            shouldPersist = true;
        }
        if (!isPlanValid(plan)) {
            plan = deepSeekUtils.repairPlan(context, plan, planInstruction(profile));
            telemetry.incrementCurrent("planStructureRepairs", 1);
            shouldPersist = true;
        }
        validatePlan(plan);
        if (mediaId != null && shouldPersist) {
            checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), plan);
        }
        return plan;
    }

    private AgentState executeRound(Long mediaId,
                                    VideoContext context,
                                    AgentState.AgentPlan plan,
                                    AgentState.CriticResult previousCritique,
                                    int round,
                                    long runStartedNanos,
                                    ModeProfile profile) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Executor 正在按计划生成结构化产物", TaskStage.EXECUTOR_STARTED);
        AnalysisResult result = deepSeekUtils.execute(context, plan, previousCritique, executeInstruction(profile));
        AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
        if (mediaId != null) {
            checkpointService.saveExecutionState(mediaId, draft, modeOf(profile));
            publishStage(mediaId, context.userGoal(), modeOf(profile),
                    "Executor 草稿已保存，开始校验证据", TaskStage.EXECUTOR_COMPLETED);
        }
        checkBudget(runStartedNanos, "Executor");
        return critiqueRound(mediaId, context, plan, result, round, profile);
    }

    private AgentState critiqueRound(Long mediaId,
                                     VideoContext context,
                                     AgentState.AgentPlan plan,
                                     AnalysisResult result,
                                     int round,
                                     ModeProfile profile) {
        publishStage(mediaId, context.userGoal(), modeOf(profile),
                "Critic 正在核验目标覆盖与时间戳证据", TaskStage.CRITIC_STARTED);
        AgentState.CriticResult critique = normalizeCritique(
                deepSeekUtils.critique(context, plan, result, criticInstruction(profile)));
        critique = enforceStructureBounds(result, critique, profile);
        critique = enforceEvidenceBounds(context, result, critique);
        // 纯风格类 feedback 不值得再烧一整轮 Executor：事实证据、目标覆盖、结构都已通过时，
        // 保留 Critic 的建议但按通过处理；有缺口/无证据结论/需要时间戳时仍然严格重跑。
        if (advisoryPassEnabled && !critique.passed()
                && !hasBlockingCritique(critique, result, profile)) {
            telemetry.incrementCurrent("criticAdvisoryPasses", 1);
            critique = new AgentState.CriticResult(
                    true,
                    critique.feedback(),
                    critique.missingRequirements(),
                    critique.unsupportedClaims(),
                    critique.requiredTimestamps());
        }
        telemetry.incrementCurrent("criticRounds", 1);
        if (critique.passed()) telemetry.incrementCurrent("criticPassed", 1);

        AgentState state = new AgentState(context.userGoal(), plan, result, critique, round);
        if (mediaId != null) {
            checkpointService.saveCriticState(mediaId, state, modeOf(profile));
            String message;
            TaskStage stage;
            if (critique.passed()) {
                message = "Critic 校验通过，正在整理结构化结果";
                stage = TaskStage.CRITIC_PASSED;
            } else if (round >= maxRounds) {
                message = "Critic 达到最大校验轮次，正在保留警告并生成结果";
                stage = TaskStage.ANALYSIS_COMPLETED_WITH_WARNINGS;
            } else if (isRewriteOnlyRetry()) {
                message = "Critic 判定疑似幻觉，不再补检索，按反馈重写";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            } else if (requiresEvidenceRefresh(critique)) {
                message = "Critic 发现证据缺口，正在定向补充证据";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            } else {
                message = "Critic 发现目标覆盖或结构问题，正在按反馈重写";
                stage = TaskStage.CRITIC_RETRY_REQUIRED;
            }
            publishStage(mediaId, context.userGoal(), modeOf(profile), message, stage);
        }
        return state;
    }

    private void validateContext(VideoContext context) {
        if (context == null || context.userGoal() == null || context.userGoal().isBlank()
                || context.segments() == null || context.segments().isEmpty()
                || context.segments().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Agent 需要目标和至少一个视频片段");
        }
    }

    private void validatePlan(AgentState.AgentPlan plan) {
        if (!isPlanValid(plan)) {
            throw new IllegalStateException("Planner 返回了无效任务列表");
        }
    }

    private boolean isPlanValid(AgentState.AgentPlan plan) {
        return plan != null && plan.understoodGoal() != null && !plan.understoodGoal().isBlank()
                && plan.tasks() != null && !plan.tasks().isEmpty()
                && plan.tasks().size() <= MAX_PLAN_TASKS
                && plan.tasks().stream().noneMatch(
                task -> task == null || task.isBlank() || task.length() > 500);
    }

    private void validateResult(AnalysisResult result, ModeProfile profile) {
        if (!isResultValid(result, profile)) {
            throw new IllegalStateException("Executor 未生成完整结构化结果");
        }
    }

    private boolean isResultValid(AnalysisResult result, ModeProfile profile) {
        boolean commonFieldsValid = result != null
                && result.title() != null && !result.title().isBlank()
                && result.conclusions() != null && !result.conclusions().isEmpty()
                && result.evidence() != null && !result.evidence().isEmpty();
        if (!commonFieldsValid || profile == null || profile.requiredSectionKeys().isEmpty()) {
            return commonFieldsValid;
        }
        List<String> presentKeys = result.sections().stream()
                .filter(section -> section != null && section.key() != null
                        && !section.key().isBlank() && !section.items().isEmpty())
                .map(section -> section.key().trim())
                .distinct()
                .toList();
        return presentKeys.containsAll(profile.requiredSectionKeys());
    }

    private AgentState.CriticResult enforceEvidenceBounds(VideoContext context,
                                                           AnalysisResult result,
                                                           AgentState.CriticResult critique) {
        critique = normalizeCritique(critique);
        // 结构化证据核验：把 core.evidence 的拒绝原因、音画冲突与重试策略写进运行时 trace，
        // 让 Critic 按原因分流，而不是所有失败都无差别重跑一轮检索。
        EvidenceAuditReport report = evidenceVerificationService.auditResult(context, result);
        EvidenceRetryPolicy.Decision retry = EvidenceRetryPolicy.decide(report);
        telemetry.sectionCurrent("evidence", report.toMap());
        telemetry.sectionCurrent("retry", EvidenceRetryPolicy.toMap(retry));
        telemetry.conflictsCurrent(report.conflictMaps());
        telemetry.incrementCurrent("evidenceRetryable", report.retryableRejections());
        telemetry.incrementCurrent("evidenceHallucination", report.hallucinationRejections());
        if (!report.notes().isEmpty()) {
            log.debug("evidence_audit total={} accepted={} retryable={} hallucination={} notes={}",
                    report.total(), report.accepted(), report.retryableRejections(),
                    report.hallucinationRejections(), report.notes());
        }
        boolean hasDeclaredProblems = !critique.feedback().isEmpty()
                || !critique.missingRequirements().isEmpty()
                || !critique.unsupportedClaims().isEmpty()
                || !critique.requiredTimestamps().isEmpty();
        if (critique.passed() && hasDeclaredProblems) {
            critique = new AgentState.CriticResult(
                    false,
                    critique.feedback(),
                    critique.missingRequirements(),
                    critique.unsupportedClaims(),
                    critique.requiredTimestamps());
        }
        if (!critique.passed()
                && critique.feedback().isEmpty()
                && critique.missingRequirements().isEmpty()
                && critique.unsupportedClaims().isEmpty()
                && critique.requiredTimestamps().isEmpty()) {
            critique = new AgentState.CriticResult(
                    false,
                    List.of("重新检查目标覆盖、结构完整性和证据绑定"),
                    List.of(), List.of(), List.of());
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) return critique;
        List<EvidenceAuditReport.EvidenceFinding> rejected = report.findings().stream()
                .filter(finding -> !finding.accepted())
                .toList();
        List<String> unsupportedClaims = result.conclusions().stream()
                .filter(claim -> result.evidence().stream().noneMatch(
                        evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)))
                .toList();
        if (rejected.isEmpty() && unsupportedClaims.isEmpty()) return critique;

        List<String> unsupported = new ArrayList<>(critique.unsupportedClaims());
        unsupportedClaims.stream()
                .filter(claim -> !unsupported.contains(claim))
                .forEach(unsupported::add);
        rejected.stream()
                .map(finding -> "证据无法核验 [" + finding.reason() + "] @"
                        + finding.timestampMs() + "ms"
                        + (finding.retryable() ? "（定向补检索）" : "（疑似幻觉）"))
                .forEach(unsupported::add);
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.addAll(retry.feedback());
        feedback.add("为每条结论重新检索并绑定可核验的时间戳证据");
        // 只有 retryable 的拒绝会进入补检索名单；伪造来源 / 跨通道拼接直接判幻觉，不再烧一轮检索。
        List<Long> requiredTimestamps = new ArrayList<>(critique.requiredTimestamps());
        retry.retryTimestamps().stream()
                .filter(timestamp -> !requiredTimestamps.contains(timestamp))
                .forEach(requiredTimestamps::add);
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                unsupported,
                requiredTimestamps);
    }

    private AgentState.CriticResult enforceStructureBounds(AnalysisResult result,
                                                            AgentState.CriticResult critique,
                                                            ModeProfile profile) {
        critique = normalizeCritique(critique);
        List<String> structural = structureFeedback(result, profile);
        if (structural.isEmpty()) return critique;
        List<String> feedback = new ArrayList<>(critique.feedback());
        feedback.addAll(structural);
        return new AgentState.CriticResult(
                false,
                feedback,
                critique.missingRequirements(),
                critique.unsupportedClaims(),
                critique.requiredTimestamps());
    }

    /** 只包含确定性结构检查，供“是否值得重跑”的判断复用。 */
    private List<String> structureFeedback(AnalysisResult result, ModeProfile profile) {
        List<String> feedback = new ArrayList<>();
        if (result == null || result.title() == null || result.title().isBlank()) {
            feedback.add("补充明确的产物标题");
        }
        if (result == null || result.conclusions() == null || result.conclusions().isEmpty()) {
            feedback.add("补充覆盖 Planner 任务的核心结论");
        }
        if (result == null || result.evidence() == null || result.evidence().isEmpty()) {
            feedback.add("为核心结论补充带时间戳的 ASR 或 OCR 证据");
        }
        List<String> missingSections = missingSectionKeys(result, profile);
        if (!missingSections.isEmpty()) {
            feedback.add("补充当前分析模式要求的结构化段落: " + String.join(", ", missingSections));
        }
        return feedback;
    }

    /** 有事实/覆盖/结构问题才值得重跑；其余 feedback 视为建议。 */
    private boolean hasBlockingCritique(AgentState.CriticResult critique,
                                        AnalysisResult result,
                                        ModeProfile profile) {
        return !structureFeedback(result, profile).isEmpty()
                || !safeList(critique.missingRequirements()).isEmpty()
                || !safeList(critique.unsupportedClaims()).isEmpty()
                || !safeList(critique.requiredTimestamps()).isEmpty();
    }

    private VideoContext contextForRetry(Long mediaId,
                                         VideoContext fullContext,
                                         VideoContext selectedContext,
                                         AgentState.CriticResult critique,
                                         ModeProfile profile) {
        if (isRewriteOnlyRetry()) {
            // 伪造来源 / 跨通道拼接属于模型幻觉，补检索不会修好它，直接重写省一轮检索开销。
            telemetry.incrementCurrent("criticHallucinationSkips", 1);
            return selectedContext;
        }
        if (!requiresEvidenceRefresh(critique)) {
            telemetry.incrementCurrent("criticRewriteOnlyRetries", 1);
            return selectedContext;
        }
        telemetry.incrementCurrent("criticEvidenceRefreshes", 1);
        VideoContext refined = longVideoContextService.refineForCritique(
                mediaId, fullContext, selectedContext, critique);
        publishStage(mediaId, fullContext.userGoal(), modeOf(profile),
                "已按 Critic 反馈补充定向证据", TaskStage.EVIDENCE_REFRESHED);
        return refined;
    }

    private boolean requiresEvidenceRefresh(AgentState.CriticResult critique) {
        return critique != null
                && (!safeList(critique.requiredTimestamps()).isEmpty()
                || !safeList(critique.missingRequirements()).isEmpty()
                || !safeList(critique.unsupportedClaims()).isEmpty());
    }

    /** 读取本轮 trace 里由 EvidenceRetryPolicy 写入的策略决策。 */
    private boolean isRewriteOnlyRetry() {
        Object section = telemetry.currentSection("retry");
        return section instanceof Map<?, ?> map
                && EvidenceRetryPolicy.Action.REWRITE_ONLY.name().equals(map.get("action"));
    }

    private AgentState.AgentPlan revisePlanForRetry(Long mediaId,
                                                    VideoContext context,
                                                    AgentState.AgentPlan currentPlan,
                                                    AgentState.CriticResult critique,
                                                    ModeProfile profile) {
        if (critique == null || safeList(critique.missingRequirements()).isEmpty()) return currentPlan;

        try {
            AgentState.AgentPlan revisedPlan = deepSeekUtils.replan(
                    context, currentPlan, critique, planInstruction(profile));
            validatePlan(revisedPlan);
            telemetry.incrementCurrent("planRevisions", 1);
            if (mediaId != null) {
                checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), revisedPlan);
                taskEventService.publishAnalysis(mediaId, context.userGoal(), modeOf(profile),
                        TaskStatus.of(TaskStatus.State.PROCESSING, "Planner 根据 Critic 反馈补充了遗漏任务"),
                        TaskStage.PLAN_COMPLETED);
            }
            return revisedPlan;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("planRevisionFallbacks", 1);
            log.warn("agent_replan_failed mediaId={}, fallback to current plan", mediaId, e);
            return currentPlan;
        }
    }

    private void publishStage(Long mediaId,
                              String goal,
                              AnalysisMode mode,
                              String message,
                              TaskStage stage) {
        if (mediaId == null) return;
        taskEventService.publishAnalysis(mediaId, goal, mode,
                TaskStatus.of(TaskStatus.State.PROCESSING, message), stage);
    }

    private List<String> missingSectionKeys(AnalysisResult result, ModeProfile profile) {
        if (profile == null || profile.requiredSectionKeys().isEmpty()) return List.of();
        List<String> presentKeys = result == null || result.sections() == null
                ? List.of()
                : result.sections().stream()
                        .filter(section -> section != null
                                && section.key() != null
                                && !section.key().isBlank()
                                && section.items() != null
                                && !section.items().isEmpty())
                        .map(section -> section.key().trim())
                        .distinct()
                        .toList();
        return profile.requiredSectionKeys().stream()
                .filter(required -> !presentKeys.contains(required))
                .toList();
    }

    private void checkBudget(long startedNanos, String completedStage) {
        AgentExecutionBudget.check(completedStage);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        AgentTelemetry.BudgetUsage usage = telemetry.currentUsage();
        String reason = null;
        if (elapsedMs > maxDurationMs) {
            reason = "Agent 超过最大执行时长 " + maxDurationMs + "ms";
        } else if (usage.estimatedTokens() > maxEstimatedTokens) {
            reason = "Agent 超过最大 Token 预算 " + maxEstimatedTokens;
        } else if (maxEstimatedCost > 0 && usage.estimatedCost() > maxEstimatedCost) {
            reason = "Agent 超过最大成本预算 " + maxEstimatedCost;
        }
        if (reason == null) return;
        telemetry.incrementCurrent("budgetTerminations", 1);
        throw new BudgetExceededException(completedStage + " 后终止：" + reason);
    }

    public static class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String message) {
            super(message);
        }

        public BudgetExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private AgentExecutionBudget.DeadlineExceededException findDeadline(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof AgentExecutionBudget.DeadlineExceededException deadline) {
                return deadline;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private AgentState.CriticResult normalizeCritique(AgentState.CriticResult critique) {
        if (critique == null) {
            return new AgentState.CriticResult(
                    false, List.of("Critic 未返回有效结果"),
                    List.of(), List.of(), List.of());
        }
        return new AgentState.CriticResult(
                critique.passed(),
                safeList(critique.feedback()),
                safeList(critique.missingRequirements()),
                safeList(critique.unsupportedClaims()),
                safeList(critique.requiredTimestamps()));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    // profile 为空(GENERAL)时返回空指令/GENERAL 模式,使 prompt 与 checkpoint 键都与引入模式前一致。
    private static AnalysisMode modeOf(ModeProfile profile) {
        return profile == null ? AnalysisMode.GENERAL : profile.mode();
    }

    private static String planInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.planInstruction();
    }

    private static String executeInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.executeInstruction();
    }

    private static String criticInstruction(ModeProfile profile) {
        return profile == null ? "" : profile.criticInstruction();
    }
}
