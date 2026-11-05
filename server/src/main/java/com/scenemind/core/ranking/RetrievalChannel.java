package com.scenemind.core.ranking;

/**
 * 参与融合的独立召回通道。
 *
 * <p>三个通道的分数来自完全不同的分布：语义通道是 cosine 或向量库返回的相似度，
 * 词法通道是关键词命中率，视觉通道是 OCR 文本命中率。它们不能放进同一个线性加权公式里，
 * 因此每个通道只贡献「排名」，不贡献「分数」。</p>
 */
public enum RetrievalChannel {
    SEMANTIC("语义召回", "语音转写 / 片段摘要的向量相似度"),
    LEXICAL("词法召回", "目标关键词在转写文本中的覆盖"),
    VISUAL("视觉召回", "目标关键词在画面 OCR 文本中的覆盖");

    private final String displayName;
    private final String description;

    RetrievalChannel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
