# 变更清单与个人贡献边界

> 这份文档用于回答两个问题：**哪些是沿用、哪些是我做的**；**每一处改造怎么验证**。
> 面试时建议直接按「基线 → 发现的问题 → 我的设计 → 验证方式」四段式讲，而不是罗列功能。

## 1. 边界总览

| 层次 | 内容 | 归属 |
| --- | --- | --- |
| 接入层 | Vue 3 工作台、浏览器扩展、SSE 阶段推送 | 沿用，未改动 |
| 编排层 | 分片上传/断点续传、RocketMQ 削峰、幂等锁、令牌桶、重试与降级、Checkpoint、失败任务重投 | 沿用，未改动 |
| 上下文层 | FFmpeg 音频切片、场景关键帧、ASR/OCR 并行与容错、VideoContext / VideoChunk | 沿用，未改动 |
| Agent 层 | Planner / Executor / Critic、四类模式路由、结构化产物、两轮预算 | 沿用，未改动 |
| 存储层 | MySQL / Redis / MinIO / Qdrant、Flyway 迁移 | 沿用，未改动 |
| **策略层：检索排序** | **RRF 多通道融合、时间窗覆盖、近重复抑制、自适应 TopK、可解释诊断、配置化** | **本次主导设计与实现** |
| **策略层：证据校验** | **结构化拒绝原因、通道隔离、跨通道拼接假阳性防护、音画冲突检测、诊断相似度** | **本次主导设计与实现** |
| **策略层：离线评测** | **确定性回放、指标体系、A/B 基线、自检断言、CI 回归门禁** | **本次主导设计与实现** |
| 工程化 | 包名规范化、Spring 适配层、单测、CI 分层、脚本、架构决策记录与求职文档 | 本次主导 |

一句话概括：**基础设施不动，重做「检索 → 证据 → 评测」这条决定结果质量的链路。**

## 2. 逐项变更清单

| # | 模块 | 改造前 | 改造后 | 类型 | 验证 |
| --- | --- | --- | --- | --- | --- |
| 1 | 融合排序 | `0.6*semantic + 0.25*lexical + 0.15*visual` 线性加权，量纲不一致时排序漂移 | 三通道独立召回 + RRF `Σ w/(k+rank)`，只用名次 | 重构 | `HybridRankerTest.rrfIsInvariantToMonotonicScoreRescaling`；评测量纲稳定性 0.500 → 1.000 |
| 2 | 跨时段覆盖 | 固定 `TOP_K=3` 个 chunk 后展开全部 segment，结果可能挤在同一段 | 第一轮每 5 分钟时间窗取一条，名额不足再按融合分补足 | 新增 | `HybridRankerTest.spreadsResultsAcrossDistinctTimeWindows` |
| 3 | 近重复抑制 | 无；相邻 ASR 切片/OCR 复拍重复占据名额 | 时间相邻 + 中文字符 bigram Jaccard 超阈值即抑制 | 新增 | `HybridRankerTest.suppressesNearDuplicateSegmentsInsideTheSameWindow`；评测冗余率 0.143 → 0.000 |
| 4 | 召回预算 | 硬编码常量 | `TopKPolicy`：单通道弱证据扩召回、截断平台扩一位、三通道强主导收缩 | 新增 | `TopKPolicyTest` 4 个用例 |
| 5 | 策略参数 | `private static final` 散落在服务类 | `RankingConfig` + `scenemind.ranking.*` 外部化配置 + Spring Bean | 重构 | `RankingConfiguration`；`HybridRankerTest.invalidConfigIsRejected` |
| 6 | 排序可解释性 | 只有分数，线上无法解释排序 | `RankingResult` 返回每结果的通道名次、选中阶段与全局诊断；写入埋点、debug 日志 | 新增 | 评测报告「本次融合诊断」小节 |
| 7 | 证据判定 | `supported()` 返回 boolean，失败原因不可知 | `AuditResult` + 9 类 `RejectionReason` + `retryable` 标记 | 重构 | `EvidenceAuditorTest` 7 个用例 |
| 8 | 通道一致性 | `ASR+OCR` 把两路文本直接拼接，跨通道拼接的假证据会通过 | 分别检查单通道，仅拼接串命中判定为 `CROSS_CHANNEL_SPLICE` 并拒绝 | 修复 | `EvidenceAuditorTest.rejectsEvidenceThatOnlyExistsBySplicingAsrAndOcr` |
| 9 | 拒绝诊断 | 无 | 二元组覆盖率区分「改写」与「编造」；归一化支持全半角与标点 | 新增 | `EvidenceAuditorTest.rejectsTextThatOnlyLooksSimilarToTheSource` |
| 10 | 音画冲突 | 未识别，模型可能挑一个日期直接作答 | `ConflictDetector` 抽取中/阿数字日期，冲突作为一等信号输出 | 新增 | `ConflictDetectorTest` 4 个用例；评测回归 |
| 11 | 评测入口 | `OfflineAgentEvaluationRunner` 仍调用真实 LLM，需 API Key、不可复现 | `core.eval` 确定性回放：`javac` + `java` 即可跑，8 条内置断言 | 重构 | `OfflineEvaluatorTest.evaluationIsFullyDeterministic` |
| 12 | 指标体系 | 5 个在线指标 | 新增 Recall@K、时间覆盖率、冗余率、量纲稳定性；在线侧新增 `evidenceRedundancyRate` / `temporalSpread` / `conflictFlagged` | 新增 | `docs/benchmark-report.md` |
| 13 | 回归门禁 | 无 | CI 独立 `core-evaluation` job，重生成报告并 `git diff --exit-code` | 新增 | `.github/workflows/ci.yml` |
| 14 | 包名 / 构建 | `com.example` 占位包名 | `com.scenemind`；新增 `core` 纯函数层，Spring 层降级为适配层 | 重构 | `mvnw test` 全绿（含原有 14 个测试类） |
| 15 | 兼容性 | — | 证据校验 3 个 public 方法签名与语义不变，原有 4 个回归测试零修改通过 | 保障 | `EvidenceVerificationServiceTest` |

## 3. 新增文件清单

**核心内核（`server/src/main/java/com/scenemind/core/`，20 个文件，零框架依赖）**

```
ranking/  RetrievalChannel, Candidate, RankingConfig, ChannelRanking,
          RankedItem, RankingDiagnostics, RankingResult, TextSimilarity,
          LinearBaselineRanker, HybridRanker, TopKPolicy
evidence/ RejectionReason, EvidenceModels, AuditResult,
          EvidenceAuditor, ConflictDetector
eval/     BenchmarkFixtures, EvaluationMetrics, OfflineEvaluator, SceneMindBenchmark
```

**Spring 适配与配置**

```
server/src/main/java/com/scenemind/server/config/RankingConfiguration.java   （新增）
server/.../service/VideoEvidenceRetrievalService.java                        （重写排序段）
server/.../service/EvidenceVerificationService.java                          （改为适配层 + 新 API）
server/.../service/AgentEvaluationService.java                               （新增 3 个指标）
```

**测试（6 个文件 / 26 个用例）**

```
server/src/test/java/com/scenemind/core/ranking/{HybridRankerTest,TopKPolicyTest}.java
server/src/test/java/com/scenemind/core/evidence/{EvidenceAuditorTest,ConflictDetectorTest}.java
server/src/test/java/com/scenemind/core/eval/OfflineEvaluatorTest.java
server/src/test/java/com/scenemind/server/service/EvidenceVerificationServiceAuditTest.java
```

**工程与文档**

```
scripts/run-benchmark.sh                    零依赖评测复现脚本
docs/PROJECT_OVERVIEW.md                   项目说明
docs/CONTRIBUTION_BOUNDARY.md              本文件
docs/RESUME.md                             简历成果描述
docs/INTERVIEW_TOPICS.md                   面试技术话题
docs/benchmark-report.md                   自动生成的评测报告
docs/adr/0001-rrf-over-linear-scoring.md   架构决策记录
docs/adr/0002-pure-function-core.md        架构决策记录
NOTICE.md                                  来源与依赖说明
README.md                                  重写
LICENSE                                    补充新增部分版权声明
.github/workflows/ci.yml                   新增 core-evaluation job
server/pom.xml                             groupId 规范化
```

## 4. 验证记录（本机实测）

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=<JDK21> ./mvnw -DskipTests test-compile`（server） | 退出码 0 |
| `JAVA_HOME=<JDK21> ./mvnw test`（server） | 20 个测试类、0 失败、0 错误 |
| `bash scripts/run-benchmark.sh` | 8 条自检断言全部通过，报告生成 |
| `npm ci && npm test`（client） | 见 README；依赖安装后既有测试通过 |
| `node --test`（extension） | 既有测试保持通过 |
| `javac --release 21` 单独编译 `core/` | 退出码 0（证明零框架依赖） |

> 说明：本机默认 JDK 是 26，而项目通过 Maven Enforcer 锁定 JDK 21（Lombok 注解处理在 26 上失效）。验证时显式使用 JDK 21，与 CI 一致。

## 5. 数据真实性声明

- `docs/benchmark-report.md` 的所有数字由 `SceneMindBenchmark` 实时计算生成，未手工填写；
- 数据集为**合成压力数据集**，用于暴露「量纲漂移 / 近重复 / 跨时段覆盖」三类问题，**不是线上真实流量**；
- Recall@8 与时间覆盖率在该数据集上**持平**（1.000 → 1.000），没有为了好看而调参；
- 简历与面试中应如实说明以上边界，不把合成集结果表述为生产收益。
