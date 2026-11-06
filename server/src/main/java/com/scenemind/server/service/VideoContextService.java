package com.scenemind.server.service;

import com.scenemind.core.frame.DenseFrameSelector;
import com.scenemind.core.frame.FrameMode;
import com.scenemind.core.frame.FrameModeResolver;
import com.scenemind.core.frame.FrameSample;
import com.scenemind.core.frame.FrameSelectionConfig;
import com.scenemind.core.frame.FrameSignature;
import com.scenemind.server.dto.VideoContext;
import com.scenemind.server.dto.TranscriptSegment;
import com.scenemind.server.utils.MinioUtils;
import com.scenemind.server.utils.OcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);
    private static final String EVIDENCE_OBJECT_PREFIX = "evidence-frames";
    private static final long SEGMENT_MS = 60_000L;
    private static final long FALLBACK_FRAME_INTERVAL_MS = 30_000L;
    private static final int FRAME_SIGNATURE_SIZE = 64;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

    private final SegmentedTranscriptionService transcriptionService;
    private final OcrUtils ocrUtils;
    private final MinioUtils minioUtils;
    private final ThreadPoolTaskExecutor asrExecutor;
    private final ThreadPoolTaskExecutor ocrExecutor;
    private final AgentTelemetry telemetry;
    private final FrameSelectionConfig frameSelectionConfig;
    private final VisualCaptionService visualCaptionService;
    private final int visionMaxFrames;
    private final FrameModeResolver frameModeResolver = new FrameModeResolver();
    private final DenseFrameSelector denseFrameSelector = new DenseFrameSelector();

    public VideoContextService(SegmentedTranscriptionService transcriptionService,
                               OcrUtils ocrUtils,
                               MinioUtils minioUtils,
                               @Qualifier("asrExecutor") ThreadPoolTaskExecutor asrExecutor,
                               @Qualifier("ocrExecutor") ThreadPoolTaskExecutor ocrExecutor,
                               AgentTelemetry telemetry,
                               FrameSelectionConfig frameSelectionConfig,
                               VisualCaptionService visualCaptionService,
                               @Value("${scenemind.vision.max-frames:12}") int visionMaxFrames) {
        this.transcriptionService = transcriptionService;
        this.ocrUtils = ocrUtils;
        this.minioUtils = minioUtils;
        this.asrExecutor = asrExecutor;
        this.ocrExecutor = ocrExecutor;
        this.telemetry = telemetry;
        this.frameSelectionConfig = frameSelectionConfig == null
                ? FrameSelectionConfig.defaults()
                : frameSelectionConfig;
        this.visualCaptionService = visualCaptionService == null
                ? new VisualCaptionService(false, null, null, null)
                : visualCaptionService;
        this.visionMaxFrames = Math.max(0, visionMaxFrames);
    }

    public VideoContext build(String videoPath, String userGoal) {
        return build(videoPath, userGoal, null, null, false);
    }

    public VideoContext build(String videoPath, String userGoal, String traceId) {
        return build(videoPath, userGoal, traceId, null, false);
    }

    /**
     * @param contentTitle  视频标题/文件名，用于 auto 模式下识别课程/PPT 类内容
     * @param learningIntent 是否处于学习模式；学习模式即使标题没有关键词也优先 dense
     */
    public VideoContext build(String videoPath,
                              String userGoal,
                              String traceId,
                              String contentTitle,
                              boolean learningIntent) {
        FrameMode frameMode = frameModeResolver.resolve(
                frameSelectionConfig.mode(), learningIntent, contentTitle, userGoal);
        String readableVideoPath = minioUtils.readableSource(videoPath);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        List<String> uploadedEvidenceFrames = new CopyOnWriteArrayList<>();
        CountDownLatch branchesFinished = new CountDownLatch(2);
        boolean cleanupWorkDir = true;
        try {
            Files.createDirectories(workDir);
            // 两条分支各跑各的，单路挂掉还能带着另一半信息继续往下走。
            Future<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
                    asrExecutor,
                    branchesFinished,
                    () -> transcriptionService.transcribe(
                            readableVideoPath, workDir.resolve("audio"), traceId));
            Future<BranchResult<FramePart>> frameFuture = submitBranch(
                    ocrExecutor,
                    branchesFinished,
                    () -> extractKeyFrames(
                            readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames, frameMode));
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
                BranchResult<TranscriptSegment> transcriptResult = awaitBranch(transcriptFuture, deadline);
                BranchResult<FramePart> frameResult = awaitBranch(frameFuture, deadline);
                return finishContext(
                        videoPath, userGoal, traceId, transcriptResult, frameResult, uploadedEvidenceFrames);
            } catch (TimeoutException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
            } catch (InterruptedException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VideoContext 构建被中断", e);
            } catch (ExecutionException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支执行失败", e.getCause());
            }
        } catch (Exception e) {
            deleteEvidenceFrames(uploadedEvidenceFrames);
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            if (cleanupWorkDir) {
                deleteDirectory(workDir);
            } else {
                log.warn("video_context_workdir_retained path={} reason=branch_still_running", workDir);
            }
        }
    }

    public void deleteEvidenceFrames(VideoContext context) {
        if (context == null) return;
        deleteEvidenceFrames(context.segments().stream()
                .flatMap(segment -> segment.evidenceFrames().stream())
                .distinct()
                .toList());
    }

    private void deleteEvidenceFrames(List<String> frames) {
        frames.stream()
                .filter(frame -> minioUtils.isManagedFile(frame, EVIDENCE_OBJECT_PREFIX))
                .distinct()
                .forEach(frame -> {
                    try {
                        minioUtils.removeFile(frame);
                    } catch (RuntimeException e) {
                        log.warn("evidence_frame_cleanup_failed frame={}", frame, e);
                    }
                });
    }

    private VideoContext finishContext(String videoPath,
                                       String userGoal,
                                       String traceId,
                                       BranchResult<TranscriptSegment> transcriptResult,
                                       BranchResult<FramePart> frameResult,
                                       List<String> uploadedEvidenceFrames) {
        if (transcriptResult.failed() && frameResult.failed()) {
            IllegalStateException failure = new IllegalStateException(
                    "ASR 和 OCR 分支均失败", transcriptResult.error());
            failure.addSuppressed(frameResult.error());
            throw failure;
        }
        if (transcriptResult.failed()) {
            telemetry.increment(traceId, "asrBranchFailures", 1);
            log.warn("video_context_asr_branch_failed", transcriptResult.error());
        }
        if (frameResult.failed()) {
            telemetry.increment(traceId, "ocrBranchFailures", 1);
            log.warn("video_context_ocr_branch_failed", frameResult.error());
            deleteEvidenceFrames(uploadedEvidenceFrames);
            uploadedEvidenceFrames.clear();
        }
        List<VideoContext.VideoSegment> segments = merge(transcriptResult.items(), frameResult.items());
        if (segments.isEmpty()) throw new IllegalStateException("视频未解析出有效语音或画面文字");
        return new VideoContext(videoPath, userGoal, segments);
    }

    private <T> Future<BranchResult<T>> submitBranch(
            ThreadPoolTaskExecutor executor,
            CountDownLatch branchesFinished,
            ThrowingSupplier<List<T>> work) {
        try {
            return executor.submit(() -> {
                try {
                    return BranchResult.success(work.get());
                } catch (Exception e) {
                    return BranchResult.failure(e);
                } finally {
                    branchesFinished.countDown();
                }
            });
        } catch (RuntimeException e) {
            branchesFinished.countDown();
            return CompletableFuture.completedFuture(BranchResult.failure(e));
        }
    }

    private <T> BranchResult<T> awaitBranch(Future<BranchResult<T>> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new TimeoutException("VideoContext 总时间预算已耗尽");
        return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private boolean cancelBranches(CountDownLatch branchesFinished, Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) future.cancel(true);
        }
        try {
            return branchesFinished.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<FramePart> extractKeyFrames(String videoPath,
                                             Path frameDir,
                                             String traceId,
                                             List<String> uploadedEvidenceFrames,
                                             FrameMode frameMode) throws Exception {
        Files.createDirectories(frameDir);
        List<Long> timestamps = new ArrayList<>();
        String filter = frameMode == FrameMode.DENSE
                // dense：固定间隔采样，交给下面的纯函数按 MAE/变化像素比例二次筛选。
                ? "fps=1/" + frameSelectionConfig.denseIntervalSeconds() + ",showinfo"
                : "select=eq(n\\,0)+gt(scene\\,0.35)+gte(t-prev_selected_t\\,30),showinfo";
        String outputPattern = frameDir.resolve("frame_%06d.jpg").toString();
        try {
            runCommand(List.of(
                    "ffmpeg", "-y", "-i", videoPath,
                    "-vf", filter,
                    // 用 -fps_mode 而非 -vsync：后者自 ffmpeg 5.0 起弃用、8.0 起移除，
                    // 在 ffmpeg 9 上会直接报 "Unrecognized option 'vsync'" 导致抽帧失败。
                    "-fps_mode", "vfr",
                    outputPattern
            ), timestamps);
        } catch (Exception modernFailure) {
            // ffmpeg < 5.1 不认识 -fps_mode（Ubuntu 22.04 自带 4.4 就是这种情况），
            // 清掉半成品后回退到旧参数；两条路径都失败才向上抛。
            clearDirectory(frameDir);
            timestamps.clear();
            runCommand(List.of(
                    "ffmpeg", "-y", "-i", videoPath,
                    "-vf", filter,
                    "-vsync", "vfr",
                    outputPattern
            ), timestamps);
        }

        List<Path> frameFiles;
        try (var paths = Files.list(frameDir)) {
            frameFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<FrameFile> candidates = new ArrayList<>();
        for (int i = 0; i < frameFiles.size(); i++) {
            long timestampMs = i < timestamps.size() ? timestamps.get(i) : i * FALLBACK_FRAME_INTERVAL_MS;
            candidates.add(new FrameFile(frameFiles.get(i), timestampMs));
        }

        List<FrameFile> selected = frameMode == FrameMode.DENSE
                ? selectDenseFrames(candidates, traceId)
                : dedupeSceneFrames(candidates, traceId);

        List<FramePart> result = new ArrayList<>();
        int failedFrames = 0;
        // 视觉描述只做召回，但逐帧串行调用 VLM 会把 OCR 分支拖长；按预算均匀抽样，
        // 默认最多 12 帧，既覆盖整条视频又避免 60 帧 × 单次超时。
        boolean captionEnabled = visualCaptionService.enabled();
        int captionStride = selected.isEmpty() || visionMaxFrames == 0 || visionMaxFrames >= selected.size()
                ? 1
                : (int) Math.ceil((double) selected.size() / visionMaxFrames);
        for (int frameIndex = 0; frameIndex < selected.size(); frameIndex++) {
            FrameFile candidate = selected.get(frameIndex);
            String ocrText;
            try {
                telemetry.increment(traceId, "ocrCalls", 1);
                ocrText = ocrUtils.recognize(candidate.path().toFile());
            } catch (RuntimeException e) {
                failedFrames++;
                telemetry.increment(traceId, "ocrFrameFailures", 1);
                log.warn("ocr_frame_failed frame={} timestampMs={}",
                        candidate.path().getFileName(), candidate.timestampMs(), e);
                continue;
            }
            String frameUrl;
            try {
                frameUrl = minioUtils.uploadLocalFile(
                        candidate.path().toFile(),
                        candidate.path().getFileName().toString(),
                        EVIDENCE_OBJECT_PREFIX);
                uploadedEvidenceFrames.add(frameUrl);
            } catch (Exception e) {
                telemetry.increment(traceId, "frameUploadFailures", 1);
                log.warn("evidence_frame_upload_failed frame={} timestampMs={}",
                        candidate.path().getFileName(), candidate.timestampMs(), e);
                frameUrl = videoPath + "#timestampMs=" + candidate.timestampMs();
            }
            String caption = null;
            boolean captionWanted = captionEnabled
                    && visionMaxFrames > 0
                    && frameIndex % captionStride == 0;
            if (captionWanted) {
                try {
                    caption = visualCaptionService.caption(candidate.path().toFile());
                } catch (RuntimeException e) {
                    telemetry.increment(traceId, "captionFailures", 1);
                    log.warn("visual_caption_failed frame={} timestampMs={}",
                            candidate.path().getFileName(), candidate.timestampMs(), e);
                }
                if (caption != null) {
                    telemetry.increment(traceId, "visualCaptions", 1);
                } else {
                    telemetry.increment(traceId, "captionMisses", 1);
                }
            } else if (captionEnabled) {
                telemetry.increment(traceId, "captionSkippedByBudget", 1);
            }
            result.add(new FramePart(candidate.timestampMs(), ocrText, frameUrl, caption));
        }
        if (result.isEmpty() && failedFrames > 0) {
            throw new IllegalStateException("所有 OCR 关键帧均处理失败");
        }
        return result;
    }

    /**
     * dense 模式：先按固定间隔拿到候选帧，再用纯函数选择器按「与上一张保留帧」的
     * MAE/变化像素比例筛选，避免 PPT 逐行出现时被场景检测漏掉。
     */
    private List<FrameFile> selectDenseFrames(List<FrameFile> candidates, String traceId) {
        List<FrameFile> valid = new ArrayList<>(candidates.size());
        List<FrameSignature> signatures = new ArrayList<>(candidates.size());
        List<Long> timestamps = new ArrayList<>(candidates.size());
        for (FrameFile candidate : candidates) {
            try {
                signatures.add(frameSignature(candidate.path().toFile()));
                timestamps.add(candidate.timestampMs());
                valid.add(candidate);
            } catch (Exception e) {
                // 单帧损坏不应拖垮整条 OCR 分支：跳过并继续处理其余候选帧。
                log.warn("dense_frame_decode_failed path={}", candidate.path(), e);
                deleteQuietly(candidate.path());
            }
        }
        if (valid.isEmpty()) {
            reportFrameDiagnostics(traceId, FrameMode.DENSE, candidates.size(), 0);
            return List.of();
        }

        List<FrameSample> kept = denseFrameSelector.select(signatures, timestamps, frameSelectionConfig);
        Set<Integer> keptOrdinals = new HashSet<>();
        for (FrameSample sample : kept) keptOrdinals.add(sample.ordinal());
        for (int i = 0; i < valid.size(); i++) {
            if (keptOrdinals.contains(i)) continue;
            deleteQuietly(valid.get(i).path());
        }
        reportFrameDiagnostics(traceId, FrameMode.DENSE, candidates.size(), kept.size());
        return kept.stream().map(sample -> valid.get(sample.ordinal())).toList();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("dense_frame_cleanup_failed path={}", path, e);
        }
    }

    /** scene 模式：保留原有 dHash 去重，只把候选/保留数量写进 trace。 */
    private List<FrameFile> dedupeSceneFrames(List<FrameFile> candidates, String traceId) throws Exception {
        List<FrameFile> kept = new ArrayList<>();
        Long previousHash = null;
        for (FrameFile candidate : candidates) {
            long imageHash = differenceHash(candidate.path().toFile());
            if (previousHash != null && Long.bitCount(previousHash ^ imageHash) <= 5) {
                continue;
            }
            previousHash = imageHash;
            kept.add(candidate);
        }
        reportFrameDiagnostics(traceId, FrameMode.SCENE, candidates.size(), kept.size());
        return kept;
    }

    private void reportFrameDiagnostics(String traceId, FrameMode mode, int candidates, int kept) {
        telemetry.increment(traceId, "frameCandidates", candidates);
        telemetry.increment(traceId, "framesKept", kept);
        telemetry.increment(traceId, "framesSuppressed", candidates - kept);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        diagnostics.put("candidates", candidates);
        diagnostics.put("kept", kept);
        diagnostics.put("suppressed", candidates - kept);
        diagnostics.put("maxFrames", frameSelectionConfig.maxFrames());
        if (mode == FrameMode.DENSE) {
            diagnostics.put("denseIntervalSeconds", frameSelectionConfig.denseIntervalSeconds());
            diagnostics.put("denseMaeThreshold", frameSelectionConfig.denseMaeThreshold());
        }
        telemetry.section(traceId, "frames", diagnostics);
    }

    /** 把一帧降采样成 64x64 灰度签名，口径与 bvs 的 frames.py 一致。 */
    private FrameSignature frameSignature(File imageFile) throws Exception {
        BufferedImage source = ImageIO.read(imageFile);
        if (source == null) {
            throw new IllegalArgumentException("无法读取抽帧图片: " + imageFile.getName());
        }
        BufferedImage scaled = new BufferedImage(
                FRAME_SIGNATURE_SIZE, FRAME_SIGNATURE_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, FRAME_SIGNATURE_SIZE, FRAME_SIGNATURE_SIZE, null);
        } finally {
            graphics.dispose();
        }
        var raster = scaled.getRaster();
        double[] pixels = new double[FRAME_SIGNATURE_SIZE * FRAME_SIGNATURE_SIZE];
        for (int y = 0; y < FRAME_SIGNATURE_SIZE; y++) {
            for (int x = 0; x < FRAME_SIGNATURE_SIZE; x++) {
                pixels[y * FRAME_SIGNATURE_SIZE + x] = raster.getSample(x, y, 0);
            }
        }
        return new FrameSignature(pixels);
    }

    private List<VideoContext.VideoSegment> merge(List<TranscriptSegment> transcripts, List<FramePart> frames) {
        Map<Long, SegmentBuilder> windows = new TreeMap<>();
        for (TranscriptSegment transcript : transcripts) {
            long windowStart = windowStart(transcript.startMs());
            windows.computeIfAbsent(windowStart, SegmentBuilder::new).transcripts.add(transcript.text());
        }
        for (FramePart frame : frames) {
            long windowStart = windowStart(frame.timestampMs());
            SegmentBuilder segment = windows.computeIfAbsent(windowStart, SegmentBuilder::new);
            if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
                segment.ocrTexts.add(frame.ocrText());
            }
            if (frame.caption() != null && !frame.caption().isBlank()) {
                segment.visualCaptions.add(frame.caption());
            }
            segment.evidenceFrames.add(frame.frameName());
        }
        return windows.values().stream().map(SegmentBuilder::build).toList();
    }

    private long windowStart(long timestampMs) {
        return timestampMs / SEGMENT_MS * SEGMENT_MS;
    }

    private long differenceHash(File imageFile) throws Exception {
        BufferedImage source = ImageIO.read(imageFile);
        if (source == null) return 0;
        BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }

        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (scaled.getRGB(x, y) > scaled.getRGB(x + 1, y)) hash |= 1;
            }
        }
        return hash;
    }

    private void runCommand(List<String> command, List<Long> timestamps) throws Exception {
        Path logPath = Files.createTempFile("dovideo-ffmpeg-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
            if (timestamps != null) {
                try (Stream<String> lines = Files.lines(logPath)) {
                    lines.forEach(line -> appendTimestamp(line, timestamps));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(logPath);
        }
    }

    private void appendTimestamp(String line, List<Long> timestamps) {
        if (!line.contains("showinfo")) return;
        Matcher matcher = PTS_TIME.matcher(line);
        if (matcher.find()) {
            timestamps.add((long) (Double.parseDouble(matcher.group(1)) * 1000));
        }
    }

    private void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    private record FrameFile(Path path, long timestampMs) {
    }

    private record FramePart(long timestampMs, String ocrText, String frameName, String caption) {
    }

    private record BranchResult<T>(List<T> items, Exception error) {
        private static <T> BranchResult<T> success(List<T> items) {
            return new BranchResult<>(items, null);
        }

        private static <T> BranchResult<T> failure(Exception error) {
            return new BranchResult<>(List.of(), error);
        }

        private boolean failed() {
            return error != null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static class SegmentBuilder {
        private final long startMs;
        private final List<String> transcripts = new ArrayList<>();
        private final List<String> ocrTexts = new ArrayList<>();
        private final List<String> evidenceFrames = new ArrayList<>();
        private final List<String> visualCaptions = new ArrayList<>();

        private SegmentBuilder(long startMs) {
            this.startMs = startMs;
        }

        private VideoContext.VideoSegment build() {
            return new VideoContext.VideoSegment(
                    startMs,
                    startMs + SEGMENT_MS,
                    String.join("\n", transcripts),
                    ocrTexts,
                    evidenceFrames,
                    visualCaptions
            );
        }
    }
}
