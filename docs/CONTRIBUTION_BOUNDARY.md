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
| **策略层：检索排序** | **RRF 多通道融合、通道隔离、时间窗覆盖、近重复抑制、自适应 TopK、可解释诊断、配置化** | **本次主导设计与实现** |
| **策略层：证据校验** | **结构化拒绝原因、通道隔离、跨通道拼接假阳性防护、音画冲突检测、诊断相似度、retryable 分流重试** | **本次主导设计与实现** |
| **策略层：离线评测** | **确定性回放、指标体系、A/B 基线、自检断言、CI 回归门禁** | **本次主导设计与实现** |
| 工程化 | 包名规范化、Spring 适配层、单测、CI 分层、脚本、MCP Server、架构决策记录 | 本次主导 |

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
| 11 | 评测入口 | `OfflineAgentEvaluationRunner` 仍调用真实 LLM，需 API Key、不可复现 | **新增** `core.eval` 确定性回放：`javac` + `java` 即可跑；旧入口保留，由 `agent.evaluation.enabled=false` 默认关闭 | 新增 | `OfflineEvaluatorTest.evaluationIsFullyDeterministic` |
| 12 | 指标体系 | 5 个在线指标 | 新增 Recall@K、时间覆盖率、冗余率、量纲稳定性；在线侧新增 `evidenceRedundancyRate` / `temporalSpread` / `conflictFlagged` | 新增 | `docs/benchmark-report.md` |
| 13 | 回归门禁 | 无 | CI 独立 `core-evaluation` job，重生成报告并 `git diff --exit-code` | 新增 | `.github/workflows/ci.yml` |
| 14 | 包名 / 构建 | `com.example` 占位包名 | `com.scenemind`；新增 `core` 纯函数层，Spring 层降级为适配层 | 重构 | `mvnw test` 全绿（含原有 14 个测试类） |
| 15 | 兼容性 | — | 证据校验 3 个 public 方法签名与语义不变，原有 4 个回归测试零修改通过 | 保障 | `EvidenceVerificationServiceTest` |
| 16 | 重试分流 | 所有校验失败无差别重跑一轮检索，伪造来源也照样烧 Token | `auditResult()` 聚合 9 类拒绝原因 + `retryable`；`EvidenceRetryPolicy` 纯函数分流：可修缺口按时间戳定向补检索，伪造来源 / 跨通道拼接判幻觉、只重写 | 新增 | `EvidenceRetryPolicyTest` 5 个用例；`EvidenceAuditIntegrationTest` 4 个用例 |
| 17 | 冲突进结果链路 | 冲突只存在于纯函数与埋点，用户看不到 | `TaskStatus` 带结构化 `conflicts`，结果 markdown 顶部加「音画冲突提示」警示条；SSE 完成事件与 `analysis-status` 重新打开面板都带 | 新增 | `TaskStatus` 渲染逻辑；`?demo` 可见警示条 |
| 18 | 检索诊断前端 | 诊断只在日志与埋点里，排障要翻服务端日志 | `/analysis/agent-trace` 返回 `diagnostics.retrieval`（通道名次、抑制数、覆盖窗数、notes）与 `diagnostics.evidence`、`diagnostics.retry`，前端「分析详情」展示；`agent-evaluation` 的证据冗余率 / 时间覆盖度 / 冲突标记一并展示 | 新增 | 前端 `?demo`；`/analysis/agent-trace` 接口 |
| 19 | RRF 通道隔离 | 三个 `ChannelRanking` 拿到同一份全量候选，`matchedChannels` 恒为 3，弱证据扩召回永不触发 | 内核与适配层都按 `channelsPresent` 过滤；空标注的旧数据保持原行为 | 修复 | `HybridRankerTest` 通道隔离用例 |
| 20 | 评测同链路 | 评测自行拼装 `rank()`，参数与线上脱节，改配置不影响报告 | 评测走 `fuse → TopKPolicy.decide → select`，读与线上同名的 `SCENEMIND_*` 环境变量；报告新增「生效策略配置」小节 | 重构 | `OfflineEvaluatorTest`；CI 报告 diff 门禁 |
| 21 | 自检断言 | 8 条，只覆盖相对基线不退化 | 11 条，新增 3 条绝对阈值：Recall ≥ 0.75、时间覆盖率 ≥ 0.75、冗余率 ≤ 0.20 | 新增 | `bash scripts/run-benchmark.sh` 输出 |
| 22 | MCP Server | 无，外部 Agent 只能自己调后端接口 | `mcp/`：零外部依赖、stdio JSON-RPC 2.0，把长视频交给 SceneMind 分析，并把带时间戳的证据结果返回给调用方 Agent | 新增 | `mcp/` 的 `node --test` 用例；`mcp/README.md` |

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
server/.../service/VideoEvidenceRetrievalService.java                        （重写排序段 + 通道过滤 + 诊断落 trace）
server/.../service/EvidenceVerificationService.java                          （改为适配层 + auditResult 聚合核验）
server/.../service/EvidenceRetryPolicy.java                                  （新增：重试分流纯函数）
server/.../dto/EvidenceAuditReport.java                                      （新增：聚合核验报告 DTO）
server/.../dto/TaskStatus.java                                               （新增结构化 conflicts + 警示条）
server/.../service/AgentLoopService.java                                     （接 retryable 分流与冲突）
server/.../service/AnalysisStatusService.java                                （历史结果恢复时补冲突标注）
server/.../service/AgentTelemetry.java                                       （diagnostics / conflicts 落 trace）
server/.../service/AgentEvaluationService.java                               （新增 3 个指标 + 复用 ranking 配置）
```

**测试（新增 10 个测试类；`server` 现有 24 个测试类 / 74 个用例）**

```
core 纯函数层（5 个文件 / 29 个用例）
server/src/test/java/com/scenemind/core/ranking/{HybridRankerTest,TopKPolicyTest}.java
server/src/test/java/com/scenemind/core/evidence/{EvidenceAuditorTest,ConflictDetectorTest}.java
server/src/test/java/com/scenemind/core/eval/OfflineEvaluatorTest.java

证据链路与 trace 契约（5 个文件 / 16 个用例）
server/.../service/EvidenceVerificationServiceAuditTest.java
server/.../service/EvidenceRetryPolicyTest.java
server/.../service/EvidenceAuditIntegrationTest.java
server/.../service/AgentTelemetryTest.java
server/.../eval/RealAnnotationEvaluationTest.java
```

**工程与文档**

```
scripts/dev-up.sh                           中间件启动（含 .env 强校验）
scripts/run-benchmark.sh                    零依赖评测复现脚本
scripts/smoke-test.sh                       端到端冒烟（本地用，不进 CI）
mcp/                                        MCP Server（stdio JSON-RPC 2.0，零外部依赖）
docs/PROJECT_OVERVIEW.md                    项目说明
docs/CONTRIBUTION_BOUNDARY.md               本文件
docs/benchmark-report.md                    自动生成的评测报告
docs/images/                                README 截图
docs/adr/0001-rrf-over-linear-scoring.md    架构决策记录
docs/adr/0002-pure-function-core.md         架构决策记录
docs/private/{RESUME,INTERVIEW_TOPICS}.md   面试准备材料（已 gitignore，不随仓库发布）
NOTICE.md                                   来源与依赖说明
README.md                                   重写
LICENSE                                     补充新增部分版权声明
.github/workflows/ci.yml                    新增 core-evaluation job
server/pom.xml                              groupId 规范化
.gitignore                                  忽略本地工作区与 docs/private/
```

## 4. 验证记录（本机实测）

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=<JDK21> mvnw.cmd -B test-compile`（server，Windows） | 退出码 0 |
| `JAVA_HOME=<JDK21> mvnw.cmd -B test`（server，Windows） | 24 个测试类、74 个用例、0 失败、0 错误 |
| `bash scripts/run-benchmark.sh` | 11 条自检断言全部通过，报告生成 |
| `npm test`（client，等价 `node --test src/*.test.js`） | 32 个前端用例全部通过 |
| `node --test test/*.test.js`（mcp） | 48 个用例全部通过，无需后端 |
| `javac --release 21` 单独编译 `core/` | 退出码 0（证明零框架依赖） |
| `bash scripts/smoke-test.sh` | 需要真实中间件 + API Key + 可分析的视频链接；缺依赖时按设计跳过 |

> 说明：本机默认 JDK 是 26，而项目通过 Maven Enforcer 锁定 JDK 21（Lombok 注解处理在 26 上失效）。验证时显式使用 JDK 21，与 CI 一致。
> `extension` 的用例需先 `npm ci` 安装依赖，本次未在本机复跑，因此不在此表断言结果。

## 5. 数据真实性声明

- `docs/benchmark-report.md` 的所有数字由 `SceneMindBenchmark` 实时计算生成，未手工填写；
- 数据集为**合成压力数据集**，用于暴露「量纲漂移 / 近重复 / 跨时段覆盖」三类问题，**不是线上真实流量**；
- Recall@8 与时间覆盖率在该数据集上**持平**（1.000 → 1.000），没有为了好看而调参；
- 简历与面试中应如实说明以上边界，不把合成集结果表述为生产收益。
