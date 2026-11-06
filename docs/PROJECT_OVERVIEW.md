# SceneMind 项目说明

## 1. 一句话定位

**面向长视频的证据可核验 Video Agent 工作台**：把数小时视频解析成带时间戳的多模态证据链，让模型产出的每条结论都能回到原始视频里被逐字核验。

- 项目类型：全栈 + AI 应用工程（Java 后端为主）
- 部署形态：Docker Compose 单机 / 本地可信环境，中间件默认只监听 `127.0.0.1`
- 主要技术栈：Java 21 / Spring Boot 3.5 / MyBatis-Plus / RocketMQ 5 / Redis + Redisson / MySQL 8 / MinIO / Qdrant / Vue 3 + Vite / FFmpeg / Tesseract / LangChain4j

## 2. 为什么需要这样一个系统

| 场景痛点 | 具体表现 | 系统对策 |
| --- | --- | --- |
| 任务长耗时 | 1~3 小时视频，上传 + ASR + OCR + 多次模型调用，HTTP 请求根本扛不住 | 分片上传与合并、RocketMQ 异步削峰、提交即返回 202、SSE 推送阶段 |
| 外部调用成本敏感 | Token、ASR、Embedding 都按量计费，重试和并发会重复烧钱 | 内容指纹 + 目标维度的幂等锁、用户级/全局令牌桶、有限次指数退避、AgentLoop 两轮上限 |
| 多模态信息割裂 | 语音说一套、PPT 写一套，模型容易只取其一 | ASR 60s 切片 + 场景/课程 dense 关键帧 OCR 并行抽取，融合成统一 `VideoSegment`；显式检测音画冲突 |
| 幻觉无法定位 | 模型给出流畅但找不到出处的结论 | 结论强绑定 `source + timestampMs + 原文`，Critic 闭环校验，不通过定向补检索 |
| 长视频证据稀疏 | 相关证据散落在数小时里，固定 TopK 容易全挤在同一段 | 5 分钟片段摘要 + 关键词 + 向量混合检索；**RRF 融合 + 时间窗覆盖 + 近重复抑制** |

## 3. 系统分层

```
┌─────────────────────────────────────────────────────────────┐
│ 接入层   Vue 3 工作台 / 浏览器扩展 / MCP Server / SSE 阶段推送   │
├─────────────────────────────────────────────────────────────┤
│ 编排层   Spring Boot API：鉴权、分片上传、任务投递、状态查询      │
│          RocketMQ 消费者 → 幂等检查 → Checkpoint 恢复            │
├─────────────────────────────────────────────────────────────┤
│ 上下文层 VideoContext：ASR / OCR / 关键帧 / 时间戳 → VideoChunk  │
├─────────────────────────────────────────────────────────────┤
│ 策略层 ★ com.scenemind.core（纯函数，零框架依赖）                │
│   ranking   RRF 融合 / 通道隔离 / 时间窗覆盖 / 去冗余 / 自适应预算 │
│   evidence  通道隔离校验 / 拒绝原因 / 音画冲突                    │
│   frame     dense/scene 抽帧 / MAE + 变化像素比例 / auto 选择      │
│   eval      确定性离线评测 / 指标 / CiteP/CiteR/CiteF1 / 自检断言   │
├─────────────────────────────────────────────────────────────┤
│ Agent 层  Planner → Executor → Critic（最多两轮）→ 结构化产物     │
│           证据重试分流 EvidenceRetryPolicy（纯函数）              │
├─────────────────────────────────────────────────────────────┤
│ 存储层   MySQL（Checkpoint 真源）/ Redis（热缓存）/ MinIO / Qdrant│
└─────────────────────────────────────────────────────────────┘
```

★ 标记的是 SceneMind 本次设计与实现的核心。把策略层从 Spring Service 里剥离，是为了让**排序与校验规则可以脱离中间件离线回放**：不启动 Docker、不配 API Key 就能跑 A/B 评测和回归测试。

## 4. 一次分析任务的完整链路

1. 前端按 5MB 分片上传，Redis 记录已完成分片，MinIO 保存合并文件；弱网中断可续传。
2. API 投递 RocketMQ 消息后立即返回任务 ID；Redisson 按「内容指纹 + 分析目标」加锁拦截重复消费。
3. Worker 先查幂等结果与 Checkpoint；命中则直接恢复，否则并行执行 ASR 与 OCR 两条分支。
4. FFmpeg 切分音频；抽帧按内容类型分流：对谈类走场景检测（30 秒保底采样），课程/PPT 类走 8 秒 dense 采样，再用 64×64 灰度 MAE + 变化像素比例筛选，生成 `VideoContext`。
5. 每 5 分钟生成摘要 / 关键词 / Embedding；同时为 60s 片段单独生成 segment embedding。检索优先用 chunk 粗排、segment 精排，避免同一 chunk 内所有片段共享一个语义名次。可选开启关键帧 VLM 描述（`scenemind.vision.enabled`），描述只参与视觉通道召回，不进入证据校验。
6. `HybridRanker` 对语义、词法、视觉（OCR + 可选画面描述）三条通道做 RRF 融合，按时间窗覆盖并抑制近重复，`TopKPolicy` 动态决定预算。
7. AgentLoop 生成结构化结论、证据与建议；模型可输出 `claims[] {id, text, evidenceIds[]}` 与 `evidence[].id` 做结构化绑定，旧格式自动回退到 `conclusions + evidence.claim`。`EvidenceVerificationService.auditResult()` 逐条核验来源、时间戳与原文，并汇总音画冲突；Critic 按拒绝原因分流：可修的证据缺口按时间戳定向补检索，伪造来源 / 跨通道拼接直接判幻觉、只重写不补检索。
8. 结果与 Checkpoint 落库，SSE 推送阶段与最终结果；`TaskStatus` 带结构化 `conflicts`，结果 markdown 顶部有音画冲突警示条，重新打开面板时同样带；检索、证据与重试诊断写入 trace，供 `/analysis/agent-trace` 与前端调试面板读取。支持基于同一视频继续追问，多轮上下文存 Redis。

## 5. 功能边界

**做**：长视频上传与解析、时序多模态上下文、跨时段证据检索、有证据约束的 Agent 推理、断点恢复、失败任务重投、离线策略评测与回归门禁。

**不做**（有意的取舍，不是遗漏）：

| 不做 | 原因 |
| --- | --- |
| 自研 ASR / OCR / Embedding 模型 | 属于模型团队的问题域；系统以可替换接口接入，避免把工程复杂度绑死在某个模型上 |
| 模型训练 / 微调 / RLHF | 实习生项目里，可验证的收益更可能来自检索与约束，而不是不可复现的训练实验 |
| 多租户、计费、公网级安全 | 部署目标是本地可信环境；补这些会稀释对核心链路的投入 |
| 分布式横向扩容 | 单机 Compose 已覆盖目标场景，扩容需要引入服务发现与分片策略，属于另一个量级的工程 |
| 保证任意视频的答案正确 | 音画冲突、ASR 误识别都会造成不可消除的不确定性，系统选择**标注冲突**而非替用户猜 |

## 6. 质量保障

| 手段 | 内容 |
| --- | --- |
| 单元测试 | `server` 34 个测试类 / 112 个 `@Test` 用例（其中 1 个真实标注集用例在未放数据时跳过；`bash ./mvnw test` 实测 0 失败）；`core` 纯函数层 9 个测试类 46 个用例，覆盖 RRF 不变性、通道隔离、去冗余、时间窗覆盖、拒绝原因、冲突检测、重试分流、抽帧双指标与 dense 选择、引用指标与评测确定性 |
| 确定性离线评测 | `bash scripts/run-benchmark.sh`，零外部依赖，12 条内置断言（含 Recall / 时间覆盖率 / 冗余率绝对阈值与 CiteF1 ≥ 0.75），失败非零退出 |
| CI 门禁 | `core-evaluation` job 重新生成报告并要求与提交版本字节一致；`backend` / `frontend` / `extension` 三路并行测试与构建 |
| 回归兼容 | 证据校验原有 3 个 public 方法语义不变，原有 4 个回归测试零修改通过 |
| 端到端冒烟 | `bash scripts/smoke-test.sh`：健康检查 → 登录 → 提交 URL 分析 → 轮询状态 → 断言结果含 `[mm:ss]` 证据；缺少 Key / 账号时明确跳过，本地用、不进 CI |
| 降级与可观测 | 向量库 / Embedding / 意图路由故障均有兜底；检索、证据核验、抽帧与重试决策作为结构化诊断写入 trace，前端「分析详情」可见 |

## 7. 当前状态与验证方式

- 已验证：后端全量清理编译与单测（JDK 21，34 个测试类 112 个用例、0 失败、1 跳过）、`core` 纯 JDK 编译与评测自检（12 条断言）、前端既有用例、MCP 57 个用例、CI 配置语法。
- 需要环境才能端到端验证：Docker Compose 中间件、FFmpeg / Tesseract、`SILICONFLOW_API_KEY`。`bash scripts/smoke-test.sh` 是这条路径的自动化版本。
- 已知限制：调试面板展示的是单次分析的诊断快照，还不是可回放的时间轴；抽帧模式目前是全局配置 + 学习模式自动选择，尚未做成请求级参数；VLM 画面描述默认关闭，开启后按 `scenemind.vision.max-frames` 做预算裁剪。
- 评测数据声明：`docs/benchmark-report.md` 为**合成压力数据集**上的确定性结果，用于策略回归，不代表线上效果。

## 8. 后续规划

本轮已完成：

- `retryable` 拒绝原因接进 AgentLoop，按原因选择重试策略（`EvidenceRetryPolicy` 纯函数 + 单测）；
- 检索诊断落到前端调试面板（`diagnostics.retrieval` / `diagnostics.evidence` / `diagnostics.retry`）；
- 音画冲突显式标注：结构化 `conflicts` 字段、结果 markdown 警示条、前端冲突提示；
- 课程/PPT 抽帧：`core.frame` 纯函数层、`scenemind.frame.*` 配置、`diagnostics.frames` 与前端抽帧诊断。

尚未完成：

1. 接入真实标注视频集，验证召回收益（当前只证明了鲁棒性与冗余改善）；
2. 用评测报告驱动 RRF 的 `k` 与通道权重自动寻优；
3. 单次任务的完整 trace 时间轴回放（诊断已进前端，回放还没有）；
4. 把抽帧模式做成请求级参数，支持同一视频按分析目标切换 scene/dense。
