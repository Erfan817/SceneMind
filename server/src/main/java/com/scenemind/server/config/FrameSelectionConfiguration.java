package com.scenemind.server.config;

import com.scenemind.core.frame.FrameMode;
import com.scenemind.core.frame.FrameSelectionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把抽帧策略参数外部化到 {@code scenemind.frame.*}。
 *
 * <p>课程/PPT 与对谈类视频的抽帧策略不同：前者需要固定间隔密集采样，
 * 后者更适合场景检测。配置化后不必为不同视频类型改代码发版。</p>
 */
@Configuration
public class FrameSelectionConfiguration {

    @Bean
    public FrameSelectionConfig frameSelectionConfig(
            @Value("${scenemind.frame.mode:auto}") String mode,
            @Value("${scenemind.frame.dense-interval-seconds:8}") int denseIntervalSeconds,
            @Value("${scenemind.frame.dense-mae-threshold:2.0}") double denseMaeThreshold,
            @Value("${scenemind.frame.dense-change-ratio-threshold:1.1}") double denseChangeRatioThreshold,
            @Value("${scenemind.frame.scene-mae-threshold:25.0}") double sceneMaeThreshold,
            @Value("${scenemind.frame.scene-change-ratio-threshold:0.55}") double sceneChangeRatioThreshold,
            @Value("${scenemind.frame.max-frames:60}") int maxFrames) {
        return new FrameSelectionConfig(
                FrameMode.fromNullable(mode),
                denseIntervalSeconds,
                denseMaeThreshold,
                denseChangeRatioThreshold,
                sceneMaeThreshold,
                sceneChangeRatioThreshold,
                maxFrames);
    }
}
