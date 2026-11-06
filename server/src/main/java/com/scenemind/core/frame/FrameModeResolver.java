package com.scenemind.core.frame;

import java.util.List;
import java.util.Locale;

/**
 * AUTO 模式的选择规则。
 *
 * <p>显式配置优先；否则学习模式或标题/目标里出现课程/PPT/板书等线索时走 dense，
 * 其余走 scene。规则保持确定性，便于单测与离线复现。</p>
 */
public final class FrameModeResolver {

    private static final List<String> DENSE_HINTS = List.of(
            "课程", "课件", "公开课", "网课", "教程", "讲解", "板书",
            "软考", "考研", "考试", "真题", "教材", "课堂",
            "ppt", "slide", "lecture", "course", "tutorial");

    public FrameMode resolve(FrameMode configured,
                             boolean learningIntent,
                             String contentTitle,
                             String userGoal) {
        if (configured != null && configured != FrameMode.AUTO) return configured;
        if (learningIntent) return FrameMode.DENSE;

        String hint = (safe(contentTitle) + " " + safe(userGoal)).toLowerCase(Locale.ROOT);
        return DENSE_HINTS.stream().anyMatch(hint::contains)
                ? FrameMode.DENSE
                : FrameMode.SCENE;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
