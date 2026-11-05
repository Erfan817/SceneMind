package com.scenemind.core.evidence;

import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音画冲突检测。
 *
 * <p>离线评测集里有一类专门的问题：口播说「八月一日上线」，画面 PPT 写「8月3日」。
 * 改造前的链路会把 ASR 与 OCR 文本合并进同一个片段，模型很可能只挑一个日期作答，
 * 结论看起来有证据、实际上丢了不确定性。这里在证据层显式识别冲突，
 * 把「存在冲突」作为可观测信号交給上层，而不是指望模型自觉。</p>
 */
public final class ConflictDetector {

    private static final Pattern ARABIC_DATE =
            Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern CHINESE_DATE =
            Pattern.compile("([一二三四五六七八九十]{1,3})\\s*月\\s*([一二三四五六七八九十]{1,3})\\s*日?");

    /** 一条音画冲突记录。 */
    public record Conflict(long startMs, String asrValue, String ocrValue, String detail) {
    }

    /** 一个视频的冲突检测结果。 */
    public record ConflictReport(boolean hasConflict, List<Conflict> conflicts) {
    }

    public ConflictReport detect(EvidenceCorpus corpus) {
        List<Conflict> conflicts = new ArrayList<>();
        if (corpus == null) return new ConflictReport(false, conflicts);
        for (EvidenceSegment segment : corpus.segments()) {
            Set<String> asrDates = dates(segment.transcript());
            Set<String> ocrDates = dates(segment.joinedOcr());
            if (asrDates.isEmpty() || ocrDates.isEmpty()) continue;
            boolean disjoint = asrDates.stream().noneMatch(ocrDates::contains);
            if (!disjoint) continue;
            conflicts.add(new Conflict(segment.startMs(),
                    String.join("/", asrDates), String.join("/", ocrDates),
                    "语音与画面在同一时间点给出了不同日期，结论需显式标注冲突"));
        }
        return new ConflictReport(!conflicts.isEmpty(), List.copyOf(conflicts));
    }

    /** 抽取并归一化日期为 "M月D日"，同时兼容中文数字。 */
    static Set<String> dates(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return result;
        Matcher arabic = ARABIC_DATE.matcher(text);
        while (arabic.find()) {
            result.add(arabic.group(1) + "月" + arabic.group(2) + "日");
        }
        Matcher chinese = CHINESE_DATE.matcher(text);
        while (chinese.find()) {
            int month = chineseNumber(chinese.group(1));
            int day = chineseNumber(chinese.group(2));
            if (month > 0 && day > 0) result.add(month + "月" + day + "日");
        }
        return result;
    }

    /** 支持 一~十、十X、二十X、X十X 这类常见中文数字。 */
    static int chineseNumber(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        int value = 0;
        int pending = 0;
        for (char ch : raw.toCharArray()) {
            int digit = switch (ch) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> 0;
            };
            if (ch == '十') {
                value += (pending == 0 ? 1 : pending) * 10;
                pending = 0;
            } else if (digit > 0) {
                pending = digit;
            } else {
                return 0;
            }
        }
        return value + pending;
    }
}
