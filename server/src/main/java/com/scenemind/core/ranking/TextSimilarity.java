package com.scenemind.core.ranking;

import java.util.HashSet;
import java.util.Set;

/**
 * 面向中文短文本的去重相似度工具。
 *
 * <p>长视频里相邻片段（ASR 60 秒切片、关键帧 OCR）常常重复同一句话，
 * 直接进 TopK 会让结论只覆盖一小段时间。这里用「字符二元组 Jaccard」衡量重复度：
 * 对中文无需分词，对 OCR 噪声（空格、标点、全半角）也有一定鲁棒性。</p>
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /** 归一化：去标点/空白/全角空格，统一大小写，全角字母数字转半角。 */
    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                ch = (char) (ch - 0xFEE0);
            }
            if (ch == 0x3000) ch = ' ';
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            }
        }
        return out.toString();
    }

    /** 字符二元组集合；单字文本退化成自身。 */
    public static Set<String> bigrams(String value) {
        String normalized = normalize(value);
        Set<String> grams = new HashSet<>();
        if (normalized.isEmpty()) return grams;
        if (normalized.length() == 1) {
            grams.add(normalized);
            return grams;
        }
        for (int i = 0; i < normalized.length() - 1; i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * @return [0,1] 的相似度；两段都无有效文本时返回 0（不判冗余，避免误杀空证据）
     */
    public static double jaccard(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (String gram : a) {
            if (b.contains(gram)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
