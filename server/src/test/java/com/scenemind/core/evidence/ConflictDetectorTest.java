package com.scenemind.core.evidence;

import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictDetectorTest {

    private final ConflictDetector detector = new ConflictDetector();

    @Test
    void detectsChineseSpokenDateVersusArabicOnScreenDate() {
        EvidenceCorpus corpus = new EvidenceCorpus(List.of(new EvidenceSegment(60_000, 120_000,
                "我们计划八月一日正式上线。", List.of("正式上线：8月3日"))));
        ConflictDetector.ConflictReport report = detector.detect(corpus);
        assertTrue(report.hasConflict());
        assertEquals(1, report.conflicts().size());
        assertEquals("8月1日", report.conflicts().get(0).asrValue());
        assertEquals("8月3日", report.conflicts().get(0).ocrValue());
    }

    @Test
    void noConflictWhenBothChannelsAgree() {
        EvidenceCorpus corpus = new EvidenceCorpus(List.of(new EvidenceSegment(0, 60_000,
                "我们计划 8月3日 正式上线。", List.of("正式上线：8月3日"))));
        assertFalse(detector.detect(corpus).hasConflict());
    }

    @Test
    void noConflictWhenOnlyOneChannelMentionsADate() {
        EvidenceCorpus corpus = new EvidenceCorpus(List.of(new EvidenceSegment(0, 60_000,
                "会议决定七月二十五日前完成灰度发布。", List.of("负责人：张明"))));
        assertFalse(detector.detect(corpus).hasConflict());
    }

    @Test
    void parsesChineseNumbersCorrectly() {
        assertEquals(8, ConflictDetector.chineseNumber("八"));
        assertEquals(10, ConflictDetector.chineseNumber("十"));
        assertEquals(11, ConflictDetector.chineseNumber("十一"));
        assertEquals(25, ConflictDetector.chineseNumber("二十五"));
        assertEquals(31, ConflictDetector.chineseNumber("三十一"));
    }
}
