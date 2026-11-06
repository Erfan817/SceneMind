package com.scenemind.core.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameModeResolverTest {

    private final FrameModeResolver resolver = new FrameModeResolver();

    @Test
    void explicitModeWinsOverAutoDetection() {
        assertEquals(FrameMode.SCENE, resolver.resolve(
                FrameMode.SCENE, true, "软考课程", "总结重点"));
    }

    @Test
    void learningIntentUsesDenseEvenWithoutTitleHints() {
        assertEquals(FrameMode.DENSE, resolver.resolve(
                FrameMode.AUTO, true, "普通视频", "总结一下"));
    }

    @Test
    void courseAndLectureTitlesUseDense() {
        assertEquals(FrameMode.DENSE, resolver.resolve(
                FrameMode.AUTO, false, "软考第12P 计算机网络基础", "总结重点"));
        assertEquals(FrameMode.DENSE, resolver.resolve(
                FrameMode.AUTO, false, "MIT 6.824 Lecture 3", "take notes"));
    }

    @Test
    void ordinaryTitlesUseScene() {
        assertEquals(FrameMode.SCENE, resolver.resolve(
                FrameMode.AUTO, false, "新品发布会", "确认上线日期"));
        assertEquals(FrameMode.SCENE, resolver.resolve(
                FrameMode.AUTO, false, null, null));
    }
}
