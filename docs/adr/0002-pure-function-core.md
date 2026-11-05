# ADR-0002：抽出零框架依赖的纯函数核心层

- 状态：已采纳
- 日期：2026-11-05
- 相关模块：`com.scenemind.core.*`、`server` 适配层、CI

## 背景

检索排序和证据校验原本都写在 Spring `@Service` 里，直接依赖 `DeepSeekUtils`、
`EmbeddingUtils`、`QdrantVectorStore`、`AgentTelemetry`。由此产生三个具体问题：

1. **没法写快测试**：要测排序就得 mock 一堆外部依赖，或直接起 Spring 上下文。
   原仓库的 `VideoEvidenceRetrievalService` / `AgentEvaluationService` 没有任何单测。
2. **「离线」评测名不副实**：`OfflineAgentEvaluationRunner` 仍会走
   `AiService.callJson(..., useVision=true, "")`，需要 API Key、受网络与模型版本影响，
   结果不可复现，自然也进不了 CI。
3. **策略常量硬编码**：`TOP_K`、权重、阈值散落在服务类里，A/B 调参要改代码发版。

## 备选方案

| 方案 | 优点 | 否决原因 |
| --- | --- | --- |
| 留在 Service 里，用 Mockito 补测试 | 改动最小 | 仍需构造大量 mock，测试脆弱；评测依然依赖 Spring |
| 起 `@SpringBootTest` 做集成测试 | 覆盖真实装配 | 慢、需要中间件配置，无法作为 PR 快速门禁 |
| 拆成独立 Maven 模块 | 物理隔离最彻底 | 要引入 reactor 聚合、调整父 POM 与 CI 构建顺序，对已有工程是较大的结构性改动，收益不抵风险 |
| **包级隔离 + 纯 JDK 可编译** | 零框架依赖、可独立编译运行、改动可控 | 隔离靠约定而非构建系统强制，需要 CI 校验 |

## 决策

1. 新建 `com.scenemind.core.{ranking,evidence,eval}`，**只允许 import JDK 与 core 内部类型**。
   `server` 层降级为适配器：DTO 转换、外部依赖降级、埋点、日志。
2. 内核不持有状态、不访问网络/数据库；相同输入永远得到相同输出。
3. `HybridRanker` 拆为 `fuse()` / `select()` 两个纯函数阶段，便于插入策略与分别测试。
4. 策略参数收敛到 `RankingConfig`，由 `RankingConfiguration` 从 `scenemind.ranking.*` 注入。
5. 用 **CI 强制校验隔离性**：`core-evaluation` job 只用 `javac --release 21` 编译 `core/`
   目录并运行评测，完全不经过 Maven、Spring、Docker 或 LLM；随后 `git diff --exit-code`
   校验生成的报告与提交版本一致。隔离被破坏（例如 core 里 import 了 Spring）会直接编译失败。
6. Spring 适配层保持三个原有 public 方法签名与语义不变，既有回归测试零修改通过。
7. 旧的 `OfflineAgentEvaluationRunner` **不删除**：它是启动时跑真实 LLM 的在线评估入口，由
   `@ConditionalOnProperty("agent.evaluation.enabled")` 默认关闭（`AGENT_EVALUATION_ENABLED=false`），
   不参与 CI。新增的确定性路径与它并存，避免一次性改动影响既有评估逻辑。

## 后果

**正面**

- 新增 10 个测试类，后端 24 个测试类 / 74 个用例全绿；原有 14 个测试类零修改通过。
- 评测从「需要 API Key 的手工脚本」变成「一个 JDK 就能跑、11 条断言、CI 门禁」；评测参数读与线上同名的 `SCENEMIND_*` 环境变量，报告会列出「生效策略配置」。
- 策略可解释、可回放、可 A/B，调参不改代码。

**负面 / 已知取舍**

- 包级隔离靠约定，不如独立模块强制；靠 CI 的 `javac` 单编译来兜底。
- 多了一层 DTO 适配，每个新检索字段要在两处映射。
- 降级、重试、埋点留在适配层，内核看不到运行时状态——这是刻意的职责切分，
  但也意味着内核自己无法感知「当前是否处于降级模式」。

## 验证

- `javac --release 21 -d out $(find core -name '*.java')` 退出码 0。
- `bash scripts/run-benchmark.sh` 11 条自检断言全部通过。
- `cd server && bash ./mvnw test`：24 个测试类 / 74 个用例 / 0 失败。
- `.github/workflows/ci.yml` 的 `core-evaluation` job 作为长期门禁。
