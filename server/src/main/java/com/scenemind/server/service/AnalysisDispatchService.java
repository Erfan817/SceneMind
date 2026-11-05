package com.scenemind.server.service;

import com.scenemind.server.common.ErrorCode;
import com.scenemind.server.dto.AgentFeedback;
import com.scenemind.server.dto.AnalysisMode;
import com.scenemind.server.dto.AnalysisTaskMsg;
import com.scenemind.server.dto.TaskStatus;
import com.scenemind.server.dto.TaskStage;
import com.scenemind.server.entity.MediaFile;
import com.scenemind.server.exception.BusinessException;
import com.scenemind.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);

    private final AiService aiService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final TaskEventService taskEventService;
    private final String analysisTopic;
    private final int dailyAiRequests;

    /** 兼容既有单测。 */
    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   RedissonClient redissonClient,
                                   TaskEventService taskEventService,
                                   String analysisTopic) {
        this(aiService, mediaService, redisTemplate, rocketMQTemplate, redissonClient,
                taskEventService, analysisTopic, 100);
    }

    @Autowired
    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   RedissonClient redissonClient,
                                   TaskEventService taskEventService,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                   String analysisTopic,
                                   @Value("${app.quota.daily-ai-requests:100}") int dailyAiRequests) {
        this.aiService = aiService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redissonClient = redissonClient;
        this.taskEventService = taskEventService;
        this.analysisTopic = analysisTopic;
        this.dailyAiRequests = dailyAiRequests;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 提交。 */
    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        return submit(mediaFile, goal, revision, AnalysisMode.GENERAL);
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = revision == null ? contentHash(mediaId) : "media-" + mediaId;
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
        String activeKey = AnalysisTaskKeys.active(AnalysisTaskKeys.mediaScope(mediaId), goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) return SubmissionResult.DUPLICATE;

        try {
            QuotaResult quota = tryAcquireQuota(mediaFile.getUserId());
            if (quota != QuotaResult.OK) {
                redisTemplate.delete(activeKey);
                return quota == QuotaResult.DAILY_LIMITED
                        ? SubmissionResult.DAILY_QUOTA_EXCEEDED
                        : SubmissionResult.RATE_LIMITED;
            }
            // 旧结果先留着。消费者真正接手后再切 Checkpoint，MQ 投递失败时用户还有结果可看。
            if (revision != null) aiService.stageRevision(revision, resolvedMode);
            rocketMQTemplate.convertAndSend(
                    analysisTopic,
                    new AnalysisTaskMsg(mediaId, action, contentHash, goal, resolvedMode.name()));
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal, resolvedMode);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }

        try {
            taskEventService.publishAnalysis(mediaId, goal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已进入异步分析队列"), TaskStage.QUEUED);
        } catch (RuntimeException eventError) {
            // MQ 已经接单，通知失败不能把任务伪装成投递失败。
            log.warn("analysis_queued_event_failed mediaId={} userId={}",
                    mediaId, mediaFile.getUserId(), eventError);
        }
        return SubmissionResult.ACCEPTED;
    }

    public boolean isActive(Long mediaId, String goal) {
        return isActive(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean isActive(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(AnalysisTaskKeys.mediaScope(mediaId), goalDigest)));
    }

    /**
     * 追问和证据检索同样会触发模型调用，统一复用分析配额，避免绕过成本护栏。
     */
    public void requireAiQuota(Long userId) {
        try {
            QuotaResult quota = tryAcquireQuota(userId);
            if (quota == QuotaResult.DAILY_LIMITED) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "今日 AI 额度已用完，请明天再试或联系管理员");
            }
            if (quota != QuotaResult.OK) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private enum QuotaResult { OK, MINUTE_LIMITED, DAILY_LIMITED }

    private QuotaResult tryAcquireQuota(Long userId) {
        RRateLimiter userLimiter = redissonClient.getRateLimiter("limit:ai:user:" + userId);
        userLimiter.trySetRate(RateType.OVERALL, USER_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        if (!userLimiter.tryAcquire()) return QuotaResult.MINUTE_LIMITED;

        RRateLimiter globalLimiter = redissonClient.getRateLimiter("limit:ai:global");
        globalLimiter.trySetRate(
                RateType.OVERALL, GLOBAL_REQUESTS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
        if (!globalLimiter.tryAcquire()) return QuotaResult.MINUTE_LIMITED;

        // 日额度：分钟级限流挡不住「每分钟 5 次、持续一天」的消耗，这里再加一道日上限。
        // 0 或负数表示关闭（仅建议本地开发使用）。
        if (dailyAiRequests <= 0) return QuotaResult.OK;
        try {
            String key = "quota:ai:daily:" + userId + ":"
                    + LocalDate.now(ZoneId.of("Asia/Shanghai"));
            Long used = redisTemplate.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redisTemplate.expire(key, Duration.ofDays(2));
            }
            if (used != null && used > dailyAiRequests) return QuotaResult.DAILY_LIMITED;
        } catch (RuntimeException e) {
            // 配额存储故障时放行，避免把正常业务整体挡死；同时留下告警日志。
            log.warn("daily_quota_unavailable userId={}", userId, e);
        }
        return QuotaResult.OK;
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, mediaService.contentHash(mediaId));
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DAILY_QUOTA_EXCEEDED,
        DUPLICATE,
        FAILED
    }
}
