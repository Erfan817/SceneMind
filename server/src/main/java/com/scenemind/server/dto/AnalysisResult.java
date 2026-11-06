package com.scenemind.server.dto;

import java.util.List;

/**
 * 固定 Agent 产物结构，避免模型只返回一段无法继续处理的自由文本。
 *
 * <p>{@code claims} 是结构化的结论-证据绑定：每条 claim 通过 {@code evidenceIds}
 * 指向 {@code evidence[].id}。旧模型/旧结果没有 claims 时，服务端仍按
 * {@code conclusions} + {@code evidence.claim} 的文本绑定做兼容。</p>
 */
public record AnalysisResult(
        String title,
        List<String> conclusions,
        List<Evidence> evidence,
        List<String> suggestions,
        List<Section> sections,
        List<Claim> claims
) {
    public AnalysisResult {
        title = title == null ? "未命名分析" : title.trim();
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        // 模式化产物段落。GENERAL 模式不产出,反序列化缺省时为 null → 归一为空 → 渲染与原来一致。
        sections = sections == null ? List.of() : List.copyOf(sections);
        claims = claims == null ? List.of() : claims.stream()
                .filter(claim -> claim != null && !claim.text().isBlank())
                .toList();
    }

    /** 兼容旧构造：没有结构化 claims 时只按 conclusions 渲染。 */
    public AnalysisResult(String title,
                          List<String> conclusions,
                          List<Evidence> evidence,
                          List<String> suggestions,
                          List<Section> sections) {
        this(title, conclusions, evidence, suggestions, sections, List.of());
    }

    /**
     * 一条结构化结论。
     *
     * @param id          稳定标识（模型输出，如 c1）
     * @param text        结论正文
     * @param evidenceIds 该结论引用的证据 id 列表
     */
    public record Claim(String id, String text, List<String> evidenceIds) {
        public Claim {
            id = id == null ? "" : id.trim();
            text = text == null ? "" : text.trim();
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record Evidence(
            long timestampMs,
            String source,
            String content,
            String claim,
            String id
    ) {
        public Evidence {
            if (timestampMs < 0) throw new IllegalArgumentException("evidence timestamp cannot be negative");
            source = source == null ? "UNKNOWN" : source.trim();
            content = content == null ? "" : content.trim();
            claim = claim == null ? "" : claim.trim();
            id = id == null ? "" : id.trim();
        }

        /** 兼容旧构造：没有结构化 id 的证据仍可被文本 claim 绑定。 */
        public Evidence(long timestampMs, String source, String content, String claim) {
            this(timestampMs, source, content, claim, "");
        }
    }

    /**
     * 模式化产物的一个段落:{@code key} 供程序识别(如 outline/quiz/highlights),
     * {@code title} 面向用户展示,{@code items} 为该段落的要点列表。
     */
    public record Section(String key, String title, List<String> items) {
        public Section {
            key = key == null ? "" : key.trim();
            title = title == null ? "" : title.trim();
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public String toMarkdown() {
        StringBuilder result = new StringBuilder("## ").append(title).append("\n\n## 核心结论\n");
        if (!claims.isEmpty()) {
            claims.forEach(claim -> {
                result.append("- ").append(claim.text());
                for (Evidence item : evidenceFor(claim)) {
                    result.append(" [").append(formatTime(item.timestampMs())).append("]");
                }
                result.append('\n');
            });
        } else {
            conclusions.forEach(item -> result.append("- ").append(item).append('\n'));
        }
        result.append("\n## 视频证据\n");
        evidence.forEach(item -> result.append("- [")
                .append(formatTime(item.timestampMs()))
                .append("] ")
                .append(item.source())
                .append("：")
                .append(item.content())
                .append('\n'));
        result.append("\n## 建议\n");
        suggestions.forEach(item -> result.append("- ").append(item).append('\n'));
        // 模式化段落追加在通用结构之后;GENERAL 无 sections,此循环不产生任何输出。
        for (Section section : sections) {
            result.append("\n## ").append(section.title()).append('\n');
            section.items().forEach(item -> result.append("- ").append(item).append('\n'));
        }
        return result.toString();
    }

    private List<Evidence> evidenceFor(Claim claim) {
        if (claim.evidenceIds().isEmpty() || evidence.isEmpty()) return List.of();
        return claim.evidenceIds().stream()
                .map(id -> evidence.stream().filter(item -> id.equals(item.id())).findFirst().orElse(null))
                .filter(item -> item != null)
                .toList();
    }

    private static String formatTime(long timestampMs) {
        long seconds = timestampMs / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
