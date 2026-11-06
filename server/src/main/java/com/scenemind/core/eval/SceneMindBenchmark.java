package com.scenemind.core.eval;

import com.scenemind.core.evidence.AuditResult;
import com.scenemind.core.evidence.ConflictDetector;
import com.scenemind.core.evidence.EvidenceAuditor;
import com.scenemind.core.evidence.EvidenceModels.EvidenceClaim;
import com.scenemind.core.evidence.EvidenceModels.EvidenceCorpus;
import com.scenemind.core.evidence.EvidenceModels.EvidenceSegment;
import com.scenemind.core.evidence.RejectionReason;
import com.scenemind.core.ranking.Candidate;
import com.scenemind.core.ranking.RankedItem;
import com.scenemind.core.ranking.RankingConfig;
import com.scenemind.core.ranking.RetrievalChannel;
import com.scenemind.core.ranking.TopKPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 零依赖的评测 / 自检入口。
 *
 * <pre>
 *   javac -d out $(find core -name "*.java")
 *   java -cp out com.scenemind.core.eval.SceneMindBenchmark --out docs/benchmark-report.md
 * </pre>
 *
 * <p>不依赖 JUnit、Spring、Maven 或任何外部服务，CI 之外的任意一台有 JDK 的机器
 * 都能复现同一份指标。内置断言失败时以非零码退出，可直接接进流水线。</p>
 */
public final class SceneMindBenchmark {

    private SceneMindBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        RankingConfig config = configFromEnvironment();
        TopKPolicy policy = policyFromEnvironment();
        OfflineEvaluator.Report report = OfflineEvaluator.evaluate(config, policy);
        List<CheckResult> evidenceChecks = evidenceRegression();
        CitationMetrics citation = citationRegression();
        TopKPolicy.Decision topKDecision = topKExample(policy);

        List<String> assertions = new ArrayList<>();
        check(assertions, report.hybrid().recallAtK() >= report.baseline().recallAtK(),
                "融合检索 Recall@K 不低于基线");
        check(assertions, report.hybrid().temporalCoverage() >= report.baseline().temporalCoverage(),
                "融合检索时间覆盖率不低于基线");
        check(assertions, report.hybrid().redundancyRate() <= report.baseline().redundancyRate(),
                "融合检索冗余率不高于基线");
        check(assertions, report.hybrid().rankStability() >= report.baseline().rankStability(),
                "融合检索量纲稳定性不低于基线");
        check(assertions, report.hybrid().rankStability() >= 0.999,
                "RRF 对单调分数变换完全不敏感");
        check(assertions, evidenceChecks.stream().allMatch(CheckResult::passed),
                "证据校验黄金用例全部通过");
        check(assertions, conflictDetected(),
                "音画冲突被显式识别");
        check(assertions, citation.f1() >= 0.75,
                "引用质量 CiteF1 不低于 0.75");
        check(assertions, topKDecision.action() == TopKPolicy.Action.EXPAND,
                "弱证据场景触发自适应扩召回");
        // 绝对阈值：相对断言（hybrid >= baseline）挡不住「两边一起变差」，
        // 补三条绝对下限，避免开发者重新生成报告后把劣化一起提交进去。
        check(assertions, report.hybrid().recallAtK() >= 0.75,
                "融合检索 Recall@K 绝对值不低于 0.75");
        check(assertions, report.hybrid().temporalCoverage() >= 0.75,
                "融合检索时间覆盖率绝对值不低于 0.75");
        check(assertions, report.hybrid().redundancyRate() <= 0.20,
                "融合检索冗余率绝对值不高于 0.20");

        String markdown = render(report, evidenceChecks, citation, topKDecision, assertions);
        System.out.println(markdown);

        String out = argument(args, "--out");
        if (out != null) {
            Path path = Path.of(out);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
            System.out.println("\n[written] " + path.toAbsolutePath());
        }
        long failed = assertions.stream().filter(line -> line.startsWith("FAIL")).count();
        if (failed > 0) {
            throw new IllegalStateException(failed + " self-check assertion(s) failed");
        }
        System.out.println("\n[self-check] " + assertions.size() + " assertions passed.");
    }

    private record CheckResult(String name, boolean passed, String detail) {
    }

    private static List<CheckResult> evidenceRegression() {
        EvidenceAuditor auditor = new EvidenceAuditor();
        List<CheckResult> results = new ArrayList<>();

        EvidenceCorpus course = corpus(new EvidenceSegment(120_000, 180_000,
                "前序遍历先访问根节点，然后递归访问左子树，最后访问右子树。",
                List.of("前序遍历：根节点、左子树、右子树")));
        AuditResult courseResult = auditor.audit(course,
                new EvidenceClaim(125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序"));
        results.add(new CheckResult("课程视频 / OCR 证据逐字核验",
                courseResult.accepted() && auditor.supportsClaim(course, "前序遍历顺序",
                        new EvidenceClaim(125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序")),
                courseResult.reason().name()));

        EvidenceCorpus meeting = corpus(new EvidenceSegment(0, 60_000,
                "会议决定在七月二十五日前完成灰度发布，负责人是张明。", List.of()));
        AuditResult meetingResult = auditor.audit(meeting,
                new EvidenceClaim(10_000, "ASR", "七月二十五日", "截止时间"));
        results.add(new CheckResult("会议视频 / 纯语音证据", meetingResult.accepted(),
                meetingResult.reason().name()));

        EvidenceCorpus recording = corpus(new EvidenceSegment(30_000, 60_000, "",
                List.of("npm run build", "Build completed: dist/")));
        AuditResult recordingResult = auditor.audit(recording,
                new EvidenceClaim(35_000, "OCR", "npm run build", "构建命令"));
        results.add(new CheckResult("操作录屏 / 纯画面证据", recordingResult.accepted(),
                recordingResult.reason().name()));

        EvidenceCorpus conflict = corpus(new EvidenceSegment(60_000, 120_000,
                "我们计划八月一日正式上线。", List.of("正式上线：8月3日")));
        AuditResult spliced = auditor.audit(conflict,
                new EvidenceClaim(75_000, "ASR+OCR", "上线。正式上线", "最终上线日期"));
        results.add(new CheckResult("跨通道拼接假阳性被拒绝",
                !spliced.accepted() && spliced.reason() == RejectionReason.CROSS_CHANNEL_SPLICE,
                spliced.reason().name()));

        AuditResult unknown = auditor.audit(course,
                new EvidenceClaim(125_000, "ASR_GUESS", "前序遍历", "来源"));
        results.add(new CheckResult("伪造来源被拒绝",
                !unknown.accepted() && unknown.reason() == RejectionReason.UNKNOWN_SOURCE,
                unknown.reason().name()));

        AuditResult outOfRange = auditor.audit(course,
                new EvidenceClaim(180_000, "OCR", "根节点", "边界"));
        results.add(new CheckResult("片段右开区间边界正确",
                !outOfRange.accepted() && outOfRange.reason() == RejectionReason.TIMESTAMP_OUT_OF_RANGE,
                outOfRange.reason().name()));

        return results;
    }

    private static boolean conflictDetected() {
        EvidenceCorpus conflict = corpus(new EvidenceSegment(60_000, 120_000,
                "我们计划八月一日正式上线。", List.of("正式上线：8月3日")));
        return new ConflictDetector().detect(conflict).hasConflict();
    }

    /**
     * 引用质量自检：CiteP=引用可核验率、CiteR=结论有出处率、CiteF1 为调和平均。
     * 故意留一条「编造来源」结论，验证指标不会把幻觉误报成满分。
     */
    private static CitationMetrics citationRegression() {
        EvidenceCorpus corpus = new EvidenceCorpus(List.of(
                new EvidenceSegment(0, 60_000,
                        "我们计划八月一日正式上线。", List.of("正式上线：8月3日")),
                new EvidenceSegment(60_000, 120_000,
                        "前序遍历先访问根节点，然后递归访问左子树。",
                        List.of("前序遍历：根节点、左子树、右子树")),
                new EvidenceSegment(120_000, 180_000,
                        "构建命令是 npm run build。", List.of("npm run build"))));
        return CitationMetrics.evaluate(corpus, List.of(
                new CitationMetrics.Claim("c1", "上线日期",
                        List.of(new CitationMetrics.Citation(10_000, "ASR", "八月一日"))),
                new CitationMetrics.Claim("c2", "前序遍历顺序",
                        List.of(new CitationMetrics.Citation(70_000, "OCR", "根节点、左子树、右子树"))),
                new CitationMetrics.Claim("c3", "构建命令",
                        List.of(new CitationMetrics.Citation(130_000, "ASR", "npm run build"))),
                new CitationMetrics.Claim("c4", "负责人",
                        List.of(new CitationMetrics.Citation(20_000, "ASR", "李四")))));
    }

    /**
     * 从与 Spring 同名的 {@code SCENEMIND_*} 环境变量构造评测参数。
     * 改这些变量会同时改变线上排序与本报告，CI 的报告 diff 门禁才覆盖配置变更；
     * 解析失败直接抛错，避免「配错了却静默用默认值」把门禁骗过去。
     */
    private static RankingConfig configFromEnvironment() {
        EnumMap<RetrievalChannel, Double> weights = new EnumMap<>(RetrievalChannel.class);
        weights.put(RetrievalChannel.SEMANTIC, doubleEnv("SCENEMIND_W_SEMANTIC", 1.0));
        weights.put(RetrievalChannel.LEXICAL, doubleEnv("SCENEMIND_W_LEXICAL", 1.0));
        weights.put(RetrievalChannel.VISUAL, doubleEnv("SCENEMIND_W_VISUAL", 0.8));
        return new RankingConfig(
                intEnv("SCENEMIND_RRF_K", 60),
                weights,
                longEnv("SCENEMIND_WINDOW_MS", 300_000L),
                doubleEnv("SCENEMIND_REDUNDANCY_SIMILARITY", 0.82),
                intEnv("SCENEMIND_MAX_RESULTS", 8));
    }

    private static TopKPolicy policyFromEnvironment() {
        return new TopKPolicy(
                intEnv("SCENEMIND_MIN_K", 3),
                intEnv("SCENEMIND_MAX_K", 12),
                intEnv("SCENEMIND_MIN_CHANNELS", 2),
                0.08, 0.5);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String key, int fallback) {
        return Integer.parseInt(env(key, Integer.toString(fallback)));
    }

    private static long longEnv(String key, long fallback) {
        return Long.parseLong(env(key, Long.toString(fallback)));
    }

    private static double doubleEnv(String key, double fallback) {
        return Double.parseDouble(env(key, Double.toString(fallback)));
    }

    private static TopKPolicy.Decision topKExample(TopKPolicy policy) {
        RankedItem weak = new RankedItem(
                new Candidate("weak", 0, 60_000, 0.3, 0.0, 0.0, "",
                        List.of(RetrievalChannel.SEMANTIC)),
                0.016, Map.of(RetrievalChannel.SEMANTIC, 1),
                List.of(RetrievalChannel.SEMANTIC), 0);
        List<RankedItem> fused = new ArrayList<>();
        fused.add(weak);
        for (int i = 1; i < 8; i++) {
            fused.add(new RankedItem(
                    new Candidate("c" + i, i * 300_000L, i * 300_000L + 60_000L,
                            0.2, 0.0, 0.0, "片段 " + i, List.of(RetrievalChannel.SEMANTIC)),
                    0.016 - i * 0.001, Map.of(RetrievalChannel.SEMANTIC, i + 1),
                    List.of(RetrievalChannel.SEMANTIC), 0));
        }
        return policy.decide(fused, 5);
    }

    private static EvidenceCorpus corpus(EvidenceSegment... segments) {
        return new EvidenceCorpus(List.of(segments));
    }

    private static void check(List<String> assertions, boolean condition, String name) {
        assertions.add((condition ? "PASS " : "FAIL ") + name);
    }

    private static String argument(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    private static String render(OfflineEvaluator.Report report,
                                 List<CheckResult> checks,
                                 CitationMetrics citation,
                                 TopKPolicy.Decision decision,
                                 List<String> assertions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SceneMind 离线评测报告\n\n");
        sb.append("> 本报告由 `com.scenemind.core.eval.SceneMindBenchmark` 自动生成，")
                .append("运行不依赖 Spring / 数据库 / 向量库 / LLM。\n")
                .append("> 数据集为**可复现的合成数据集**（")
                .append(report.dataset()).append("），用于策略回归，非线上真实流量。\n")
                .append("> 评测参数来自与线上同名的 `SCENEMIND_*` 环境变量，")
                .append("链路与在线一致（fuse -> TopKPolicy.decide -> select）。\n\n");

        sb.append("## 1. 检索策略 A/B\n\n");
        sb.append(String.format(Locale.ROOT,
                "- 候选片段：%d 个；标注相关片段：%d 个；请求 TopK = %d；实际预算 = %d；时间窗 = %d ms\n",
                report.candidateCount(), report.relevantCount(),
                report.config().maxResults(), report.decision().topK(),
                report.config().windowMs()));
        sb.append("- 基线：三通道原始分线性加权（0.6 / 0.25 / 0.15）\n");
        sb.append("- 改造：RRF 多通道融合 + 时间窗覆盖 + 近重复抑制\n\n");
        sb.append("| 策略 | Recall@K ↑ | 时间覆盖率 ↑ | 冗余率 ↓ | 量纲稳定性 ↑ |\n");
        sb.append("| --- | ---: | ---: | ---: | ---: |\n");
        sb.append(report.baseline().toRow("线性加权（改造前）")).append('\n');
        sb.append(report.hybrid().toRow("RRF 融合（改造后）")).append('\n');
        sb.append(String.format(Locale.ROOT, "| **变化** | **%+.3f** | **%+.3f** | **%+.3f** | **%+.3f** |\n\n",
                report.hybrid().recallAtK() - report.baseline().recallAtK(),
                report.hybrid().temporalCoverage() - report.baseline().temporalCoverage(),
                report.hybrid().redundancyRate() - report.baseline().redundancyRate(),
                report.hybrid().rankStability() - report.baseline().rankStability()));
        sb.append("> **量纲稳定性**：把语义通道分数做正的仿射变换 `37x + 5`")
                .append("（模拟远程向量库与本地 cosine 尺度不一致）后，TopK 集合的 Jaccard。")
                .append("RRF 只用名次，理论上应恒为 1.000；线性加权会因权重失衡而漂移。\n\n");

        sb.append("### 本次融合诊断\n\n");
        sb.append(String.format(Locale.ROOT,
                "- 参与融合候选 %d 个，覆盖 %d 个时间窗，抑制近重复 %d 条，第二轮补足 %d 条\n",
                report.diagnostics().candidatesSeen(), report.diagnostics().windowsCovered(),
                report.diagnostics().duplicatesSuppressed(), report.diagnostics().secondPassFills()));
        report.diagnostics().notes().forEach(note -> sb.append("- ").append(note).append('\n'));

        sb.append("\n## 2. 证据校验回归\n\n");
        sb.append("| 用例 | 结果 | 判定原因 |\n| --- | --- | --- |\n");
        checks.forEach(c -> sb.append(String.format(Locale.ROOT,
                "| %s | %s | %s |\n", c.name(), c.passed() ? "通过" : "失败", c.detail())));
        sb.append("\n音画冲突检测：")
                .append(conflictDetected() ? "已识别（语音 8月1日 / 画面 8月3日）" : "未识别")
                .append("\n\n");

        sb.append("## 3. 引用质量自检（CiteP / CiteR / CiteF1）\n\n");
        sb.append("> 引用指标把「每条结论都能回到原始时间戳」变成可计算的数字：")
                .append("CiteP 是被引用证据中通过逐字核验的比例，CiteR 是结论中至少有一条有效引用的比例，")
                .append("CiteF1 是二者的调和平均。数据集为合成自检，故意包含一条编造来源，")
                .append("验证指标不会把幻觉误报成满分。\n\n");
        sb.append(String.format(Locale.ROOT,
                "| 指标 | 数值 |\n| --- | ---: |\n"
                        + "| 引用总数 | %d |\n| 可核验引用（CiteP 分子） | %d |\n"
                        + "| 结论总数 | %d |\n| 有出处结论（CiteR 分子） | %d |\n"
                        + "| **CiteP** | **%.3f** |\n| **CiteR** | **%.3f** |\n| **CiteF1** | **%.3f** |\n\n",
                citation.evidenceTotal(), citation.evidenceSupported(),
                citation.claimTotal(), citation.claimSupported(),
                citation.precision(), citation.recall(), citation.f1()));

        sb.append("## 4. 自适应召回预算\n\n");
        sb.append(String.format(Locale.ROOT,
                "- 本次 A/B 链路决策：`%s`（TopK=%d），理由：%s\n",
                report.decision().action(), report.decision().topK(), report.decision().reason()));
        sb.append(String.format(Locale.ROOT,
                "- 弱证据样例决策：`%s`（TopK=%d），理由：%s\n\n",
                decision.action(), decision.topK(), decision.reason()));

        sb.append("## 5. 生效策略配置\n\n");
        sb.append("> 本节由环境变量实时生成。修改 `SCENEMIND_*` 后重跑评测，本节与指标都会变化，")
                .append("CI 的 `git diff --exit-code` 会要求同步提交报告，因此配置变更同样受门禁约束。\n\n");
        sb.append(String.format(Locale.ROOT,
                "- `SCENEMIND_RRF_K=%d`，`SCENEMIND_WINDOW_MS=%d`，"
                        + "`SCENEMIND_REDUNDANCY_SIMILARITY=%.2f`，`SCENEMIND_MAX_RESULTS=%d`\n",
                report.config().rrfK(), report.config().windowMs(),
                report.config().redundancySimilarity(), report.config().maxResults()));
        sb.append(String.format(Locale.ROOT,
                "- 通道权重：语义 %.2f / 词法 %.2f / 视觉 %.2f\n",
                report.config().weightOf(RetrievalChannel.SEMANTIC),
                report.config().weightOf(RetrievalChannel.LEXICAL),
                report.config().weightOf(RetrievalChannel.VISUAL)));
        sb.append("- TopKPolicy：`SCENEMIND_MIN_K` / `SCENEMIND_MAX_K` / `SCENEMIND_MIN_CHANNELS`")
                .append("（默认 3 / 12 / 2），平台阈值 0.08 / 0.50 与线上 `RankingConfiguration` 一致\n\n");

        sb.append("## 6. 自检断言\n\n");
        assertions.forEach(line -> sb.append("- ").append(line).append('\n'));
        sb.append("\n---\n\n复现命令：\n\n```bash\n")
                .append("bash scripts/run-benchmark.sh\n```\n");
        return sb.toString();
    }
}
