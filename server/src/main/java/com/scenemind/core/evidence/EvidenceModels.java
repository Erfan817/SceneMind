package com.scenemind.core.evidence;

import java.util.List;
import java.util.Objects;

/**
 * 证据校验层的输入模型，与 Spring 层的 DTO 解耦。
 */
public final class EvidenceModels {

    private EvidenceModels() {
    }

    /** 视频中一个带时间区间的多模态片段。 */
    public record EvidenceSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts
    ) {
        public EvidenceSegment {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
            transcript = transcript == null ? "" : transcript;
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        }

        public boolean covers(long timestampMs) {
            return timestampMs >= startMs && timestampMs < endMs;
        }

        public String joinedOcr() {
            return String.join(" ", ocrTexts);
        }
    }

    /** 一个视频的完整可核验上下文。 */
    public record EvidenceCorpus(List<EvidenceSegment> segments) {
        public EvidenceCorpus {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    /** 模型产出的一条证据。 */
    public record EvidenceClaim(
            long timestampMs,
            String source,
            String content,
            String claim
    ) {
        public EvidenceClaim {
            Objects.requireNonNull(source, "source");
            content = content == null ? "" : content;
            claim = claim == null ? "" : claim;
        }
    }
}
