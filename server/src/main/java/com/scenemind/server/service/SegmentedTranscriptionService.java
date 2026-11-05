package com.scenemind.server.service;

import com.scenemind.server.dto.TranscriptSegment;
import com.scenemind.server.utils.AliyunAsrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class SegmentedTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SegmentedTranscriptionService.class);
    private static final long SEGMENT_MS = 60_000L;

    private final AliyunAsrUtils aliyunAsrUtils;
    private final AgentTelemetry telemetry;
    private final int maxSegmentConcurrency;

    public SegmentedTranscriptionService(AliyunAsrUtils aliyunAsrUtils,
                                         AgentTelemetry telemetry,
                                         @org.springframework.beans.factory.annotation.Value(
                                                 "${ai.asr.segment-concurrency:3}") int maxSegmentConcurrency) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.telemetry = telemetry;
        this.maxSegmentConcurrency = Math.max(1, Math.min(8, maxSegmentConcurrency));
    }

    public List<TranscriptSegment> transcribe(String videoPath, Path audioDir, String traceId) throws Exception {
        Files.createDirectories(audioDir);
        Path outputPattern = audioDir.resolve("audio_%03d.mp3");
        runFfmpeg(videoPath, outputPattern);

        List<Path> audioFiles;
        try (var paths = Files.list(audioDir)) {
            audioFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<TranscriptSegment> result = new ArrayList<>();
        int failedSegments = 0;
        RuntimeException lastSegmentError = null;
        // 各分片之间互不依赖，串行调用只会把网络等待叠加起来。
        // 只并发调用 ASR（I/O 密集），转写结果仍按分片序号回填，输出与串行完全一致。
        int concurrency = Math.min(maxSegmentConcurrency, Math.max(1, audioFiles.size()));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "asr-segment");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<SegmentResult>> tasks = new ArrayList<>();
            for (int i = 0; i < audioFiles.size(); i++) {
                final int index = i;
                tasks.add(() -> {
                    try {
                        telemetry.increment(traceId, "asrCalls", 1);
                        return new SegmentResult(index, aliyunAsrUtils.audioToText(audioFiles.get(index).toString()), null);
                    } catch (RuntimeException e) {
                        return new SegmentResult(index, null, e);
                    }
                });
            }
            List<Future<SegmentResult>> futures = pool.invokeAll(tasks);
            for (Future<SegmentResult> future : futures) {
                SegmentResult segment = future.get();
                if (segment.error() != null) {
                    failedSegments++;
                    lastSegmentError = segment.error();
                    telemetry.increment(traceId, "asrSegmentFailures", 1);
                    log.warn("asr_segment_failed segment={} file={}", segment.index(),
                            audioFiles.get(segment.index()).getFileName(), segment.error());
                } else if (segment.text() != null && !segment.text().isBlank()) {
                    result.add(new TranscriptSegment(
                            segment.index() * SEGMENT_MS, (segment.index() + 1) * SEGMENT_MS, segment.text()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException("ASR 分片任务异常", e.getCause());
        } finally {
            pool.shutdownNow();
        }
        if (result.isEmpty() && failedSegments > 0) {
            // 必须带上 cause：AliyunAsrUtils 已经区分了「429/5xx 可重试」与「4xx 请求本身有问题」，
            // 这里若丢掉原因，消费者只能看到一个笼统的 IllegalStateException，
            // 于是参数错误也会被当成抖动反复重试整条 ASR+LLM 流水线。
            throw new IllegalStateException("所有 ASR 分片均处理失败", lastSegmentError);
        }
        return result;
    }

    public String transcribeToText(String videoPath) {
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "transcription-" + UUID.randomUUID());
        try {
            return transcribe(videoPath, workDir, null).stream()
                    .map(TranscriptSegment::text)
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败", e);
        } finally {
            deleteDirectory(workDir);
        }
    }

    private void runFfmpeg(String videoPath, Path outputPattern) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame",
                "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
                outputPattern.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("transcription_temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("transcription_temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    private record SegmentResult(int index, String text, RuntimeException error) { }
}
