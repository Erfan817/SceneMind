# SceneMind MCP Server

把 SceneMind 长视频「证据可核验」分析能力以 [Model Context Protocol](https://modelcontextprotocol.io/) 暴露给别的 Agent：对方的 Agent 把视频链接/分析目标交给本 Server，Server 调用 SceneMind 后端，把带**时间戳 / 来源 / 原文**证据的结构化结果返回给对方 Agent。

- **零外部依赖**：只用 Node.js 内置模块（`node:http` / `node:https` / `node:test` / `node:readline` …），不需要 `npm install`，不引入 `@modelcontextprotocol/sdk`。
- **Node 22+**，ESM（`"type": "module"`）。
- **stdio 传输**：换行分隔的 JSON-RPC 2.0（MCP stdio 标准），**不使用** Content-Length 帧；`stdout` 只输出 JSON-RPC 消息，所有日志走 `stderr`。
- 当前**只做 stdio**，远程 HTTP（Streamable HTTP）尚未实现，见 `docs/MCP_SERVER.md`。

---

## 快速开始

```bash
cd mcp
node --test test/*.test.js        # 48 个测试，全部内置，无需后端
SCENEMIND_DEMO=1 node src/server.js   # 手工喂一行 initialize 即可看到合法响应
```

手工验证（macOS/Linux 或 PowerShell）：

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}' \
  | SCENEMIND_DEMO=1 node src/server.js
```

---

## 环境变量（全部配置只走环境变量）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `SCENEMIND_API_BASE` | `http://127.0.0.1:9090` | 后端地址 |
| `SCENEMIND_TOKEN` | 空 | 已有 token，直接作为 `Authorization: Bearer <token>` 注入 |
| `SCENEMIND_EMAIL` | 空 | 自动登录账号。**注意**：后端登录字段是 `username`，本 Server 把该值填入 `username` |
| `SCENEMIND_PASSWORD` | 空 | 自动登录密码（配合 `SCENEMIND_EMAIL`，惰性调用 `POST /user/login` 换 token） |
| `SCENEMIND_DEMO` | 空 | `1` / `true` 开启 demo 模式：不连后端，返回内置合成数据 |
| `SCENEMIND_TIMEOUT_MS` | `15000` | 单次 HTTP 请求超时（毫秒，上限 600000） |

鉴权优先级：`SCENEMIND_TOKEN` 优先；否则若有 `SCENEMIND_EMAIL` + `SCENEMIND_PASSWORD`，在第一次业务调用前惰性登录换取 token；都没有时按「无鉴权」尽力而为（真实后端会返回 401，错误会被归一化后返回）。

---

## 安装到 MCP 客户端

把 `command` 指向 `src/server.js`，并用 `node` 启动。下面示例用绝对路径 `D:/SceneMind/mcp/src/server.js`（请按你的实际路径替换）。

### Claude Desktop — `claude_desktop_config.json`

```json
{
  "mcpServers": {
    "scenemind": {
      "command": "node",
      "args": ["D:/SceneMind/mcp/src/server.js"],
      "env": {
        "SCENEMIND_API_BASE": "http://127.0.0.1:9090"
      }
    }
  }
}
```

### Cursor — `~/.cursor/mcp.json`

```json
{
  "mcpServers": {
    "scenemind": {
      "command": "node",
      "args": ["D:/SceneMind/mcp/src/server.js"],
      "env": {
        "SCENEMIND_DEMO": "1"
      }
    }
  }
}
```

### Cline — `cline_mcp_settings.json`

```json
{
  "mcpServers": {
    "scenemind": {
      "command": "node",
      "args": ["D:/SceneMind/mcp/src/server.js"],
      "env": {
        "SCENEMIND_API_BASE": "http://127.0.0.1:9090"
      },
      "disabled": false,
      "autoApprove": []
    }
  }
}
```

> **不要把 `SCENEMIND_TOKEN` / 密码写进这些 JSON 配置文件**（它们常被同步、备份或提交到仓库）。见下方「安全说明」。

---

## 工具清单（名称前缀 `scenemind_`）

| 工具 | 输入 | 对应后端接口 | 输出要点 |
| --- | --- | --- | --- |
| `scenemind_submit_video` | `{url, goal, mode?}` | `POST /media/upload-url` | `mediaId`、入库状态、下一步 `scenemind_analyze` 建议 |
| `scenemind_analyze` | `{mediaId, goal, mode?}` | `POST /analysis/ai` | 202 已受理 / 200 已有可复用结果；不等待完成 |
| `scenemind_get_result` | `{mediaId, goal, mode?}` | `analysis-status` + `agent-evaluation` + `agent-trace` | 结构化结果：`state`、markdown 报告、证据列表、音画冲突、证据核验摘要、trace 提示；未完成只返回当前状态与阶段 |
| `scenemind_wait_for_result` | `{mediaId, goal, mode?, timeoutMs?, pollIntervalMs?}` | 轮询 `get_result` | 超时内完成返回完整结果；超时返回最后一次状态并明确标注「未完成」。默认 180s、上限 600s |
| `scenemind_search_evidence` | `{mediaId, query}` | `GET /analysis/evidence-search` | 带时间戳的命中片段（startMs/endMs、来源、snippet、transcript、ocrTexts） |
| `scenemind_ask_followup` | `{mediaId, question, goal?, mode?}` | `POST /analysis/follow-up` | 追问答案文本 |
| `scenemind_verify_evidence` | `{mediaId, goal, evidence:[{timestampMs,source,content}], mode?}` | `agent-evaluation` + `agent-trace` | 「每条证据是否可核验」的**客户端判定说明**：如实基于平台已有校验信号，不重新实现伪校验；若后端提供结构化 `evidenceAudit` 则直接采用 |

`mode` 取值：`GENERAL` / `LEARNING` / `REVIEW` / `CREATION`，缺省 `GENERAL`。`goal` 与 `mode` 共同构成后端任务身份 key，`submit → analyze → get_result/wait` 必须传一致的值。

> 说明：规格里 `submit_video` 写作 `{url?, goal, mode?}`，这里把 `url` 设为**必填**——因为该工具唯一职责就是把在线链接交给 `/media/upload-url`，没有 URL 就无法提交；`goal`、`mode` 仅用于生成下一步建议，不会在入库时传给后端。

## Resources

| URI 模板 | 内容 | mimeType |
| --- | --- | --- |
| `scenemind://task/{mediaId}/report` | 分析报告 markdown（含时间戳证据） | `text/markdown` |
| `scenemind://task/{mediaId}/trace` | 检索诊断 JSON（通道名次、覆盖时间窗、Token/成本） | `application/json` |

后端 `analysis-status` / `agent-trace` 需要 `goal` 与 `mode` 才能定位任务，URI 支持可选查询参数 `?goal=<urlencoded>&mode=GENERAL`；省略时用后端默认 goal + `GENERAL`。若分析用了自定义 goal，请在 URI 上附带，或直接用 `scenemind_get_result` 工具显式传 `goal`。

---

## Demo 模式

```bash
SCENEMIND_DEMO=1 node src/server.js
```

- **完全不连后端**：所有方法返回 `src/demoData.js` 里的内置合成数据，可离线演示与测试。
- 内置一条与 `docs/benchmark-report.md` 一致的音画冲突示例：口播「**8月1日**上线」/ 画面 OCR「**8月3日**」。
- demo 的 `get_result` 直接返回 `COMPLETED`，因此 `wait_for_result` 会立即返回，适合演示完整链路。
- demo 的 `evaluation` 附带**示例性**的结构化 `conflicts` 与 `evidenceAudit`，用来展示「后端若提供结构化信号就直接用」的代码路径；当前 Java 后端实际上只通过 `agent-evaluation` 暴露聚合信号，并不返回该对象。

---

## 安全说明

- **token / 密码不要写进配置文件**。MCP 客户端的 JSON 配置通常落盘、被同步/备份。推荐做法：在启动 MCP 客户端的 shell / 系统服务环境里 `export SCENEMIND_TOKEN=...`（或 `SCENEMIND_EMAIL` + `SCENEMIND_PASSWORD`），或用一个包装脚本从系统密钥环读取后 `exec node src/server.js`；配置文件的 `env` 里只放 `SCENEMIND_API_BASE` / `SCENEMIND_DEMO` 这类非敏感项。
- **本地可信环境边界**：SceneMind 后端的定位是「本地 / 单机可信环境」，不做多租户、计费、公网级鉴权（见仓库 `README.md` 能力边界）。本 MCP Server 是被本地 Agent 拉起的 stdio 子进程，继承调用方权限，token 等同于该用户媒体库的完整访问权。只把它接入可信的本地 Agent，不要暴露给不可信输入或公网。
- **不要把 `SCENEMIND_API_BASE` 指向不可信主机**：Bearer token 会随请求发往该地址。
- **stdout 是协议通道**：任何写到 stdout 的非 JSON 内容都会破坏 MCP 会话；日志一律走 stderr。
- **当前只有 stdio**：远程 Streamable HTTP 需要认证、TLS 与公网暴露等额外设计，列为下一步，尚未实现（见 `docs/MCP_SERVER.md`）。

---

## 目录结构

```
mcp/
├── package.json
├── README.md
├── src/
│   ├── protocol.js         纯函数：JSON-RPC 解析、分发、错误码
│   ├── httpClient.js       Result 信封解包、token 注入、超时、错误归一化
│   ├── scenemindClient.js  业务方法 + 结果聚合 + demo 分支
│   ├── tools.js            工具定义（JSON Schema）+ 校验 + 分派 + 轮询等待
│   ├── resources.js        resource 模板与读取
│   ├── demoData.js         demo 合成数据（含 8月1日/8月3日 冲突）
│   └── server.js           入口：stdin 逐行读、分发、stdout 输出、stderr 日志
└── test/
    ├── helpers/
    │   ├── rpc.js          子进程 stdio JSON-RPC 测试驱动
    │   └── fakeBackend.js  本地假 SceneMind HTTP 服务
    ├── protocol.test.js
    ├── server.test.js      假 HTTP 服务 + 子进程跑完整 tools/call
    └── demo.test.js
```
