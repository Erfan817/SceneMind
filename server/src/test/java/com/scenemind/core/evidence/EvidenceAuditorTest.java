package com.scenemind.core.evidence;

import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAuditorTest {

    private final EvidenceAuditor auditor = new EvidenceAuditor();

    private static EvidenceCorpus course() {
        return new EvidenceCorpus(List.of(new EvidenceSegment(120_000, 180_000,
                "前序遍历先访问根节点，然后递归访问左子树，最后访问右子树。",
                List.of("前序遍历：根节点、左子树、右子树"))));
    }

    @Test
    void acceptsVerbatimOcrEvidenceAndMatchingClaim() {
        EvidenceClaim claim = new EvidenceClaim(125_000, "OCR",
                "根节点、左子树、右子树", "前序遍历顺序");
        AuditResult result = auditor.audit(course(), claim);
        assertTrue(result.accepted());
        assertEquals(RejectionReason.NONE, result.reason());
        assertEquals("OCR", result.channel());
        assertTrue(auditor.supportsClaim(course(), "前序遍历顺序", claim));
    }

    @Test
    void rejectsUnknownSourcesEvenWhenTheyContainAValidSourceName() {
        for (String source : List.of("NOT_ASR", "OCR_GUESS", "ASR+WEB", "UNKNOWN_ASR_OCR")) {
            AuditResult result = auditor.audit(course(),
                    new EvidenceClaim(125_000, source, "前序遍历", "遍历"));
            assertFalse(result.accepted(), source);
            assertEquals(RejectionReason.UNKNOWN_SOURCE, result.reason(), source);
            assertFalse(result.retryable(), "来源造假不该触发重试");
        }
    }

    @Test
    void honorsExclusiveSegmentEnd() {
        for (String source : List.of("ASR", "OCR", "ASR+OCR", "asr")) {
            assertTrue(auditor.supported(course(),
                    new EvidenceClaim(120_000, source, "前序遍历", "遍历")), source);
            AuditResult outside = auditor.audit(course(),
                    new EvidenceClaim(180_000, source, "前序遍历", "遍历"));
            assertFalse(outside.accepted(), source);
            assertEquals(RejectionReason.TIMESTAMP_OUT_OF_RANGE, outside.reason(), source);
            assertTrue(outside.retryable(), "时间戳问题值得回到检索补证据");
        }
    }

    @Test
    void rejectsTextThatOnlyLooksSimilarToTheSource() {
        AuditResult result = auditor.audit(course(), new EvidenceClaim(125_000, "OCR",
                "根节点左子树不存在，因此应跳过", "前序遍历顺序"));
        assertFalse(result.accepted());
        assertEquals(RejectionReason.TEXT_NOT_FOUND, result.reason());
        assertTrue(result.similarity() > 0 && result.similarity() < 1,
                "拒绝时仍应给出覆盖率，用于区分改写与编造");
    }

    @Test
    void rejectsEvidenceThatOnlyExistsBySplicingAsrAndOcr() {
        EvidenceCorpus corpus = new EvidenceCorpus(List.of(new EvidenceSegment(60_000, 120_000,
                "我们计划八月一日正式上线。", List.of("正式上线：8月3日"))));
        AuditResult result = auditor.audit(corpus,
                new EvidenceClaim(75_000, "ASR+OCR", "上线。正式上线", "最终上线日期"));
        assertFalse(result.accepted(), "改造前的拼接匹配会放过这条假证据");
        assertEquals(RejectionReason.CROSS_CHANNEL_SPLICE, result.reason());
        assertFalse(result.retryable());
    }

    @Test
    void reportsEmptyChannelAndNullInput() {
        EvidenceCorpus blank = new EvidenceCorpus(List.of(
                new EvidenceSegment(0, 60_000, "", List.of())));
        assertEquals(RejectionReason.CHANNEL_EMPTY, auditor.audit(blank,
                new EvidenceClaim(10_000, "ASR", "任意文本", "结论")).reason());
        assertEquals(RejectionReason.NULL_INPUT, auditor.audit(null,
                new EvidenceClaim(10_000, "ASR", "任意文本", "结论")).reason());
        assertEquals(RejectionReason.EMPTY_CONTENT, auditor.audit(course(),
                new EvidenceClaim(125_000, "ASR", "，。！", "结论")).reason());
    }

    @Test
    void keywordCoverageIsDedupedAndScaleFree() {
        assertEquals(1.0, EvidenceAuditor.keywordCoverage(
                "前序遍历与灰度发布", List.of("前序遍历", "前序遍历", "灰度发布")));
        assertEquals(0.5, EvidenceAuditor.keywordCoverage(
                "前序遍历", List.of("前序遍历", "灰度发布")));
        assertEquals(1.0, EvidenceAuditor.keywordCoverage("任意输出", List.of()));
    }
}
