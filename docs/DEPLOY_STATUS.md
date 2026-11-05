# 部署状态与实测记录

> 日期：2026-11-05
> 环境：一台 **2C / 1.9GB RAM / 29GB SSD** 的 Ubuntu 22.04 云服务器（共享公网 IP + NAT 端口映射）
> 说明：本文只记录环境与结论，不包含服务器地址、账号或任何密钥。

## 1. 为什么走裸机部署

该服务器所在网络**无法访问** Docker Hub、GitHub、Let's Encrypt（国内小带宽 VPS 常见）。  
Docker Compose 方案因此不可用，改用 [`DEPLOY_BARE.md`](DEPLOY_BARE.md) 的宿主直装方式：

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| Java | OpenJDK 21.0.12 | Ubuntu apt |
| MySQL | 8.0.46 | Ubuntu apt |
| Redis | 6.0.16 | Ubuntu apt |
| MinIO | RELEASE.2025-07-23 | `dl.minio.org.cn` |
| RocketMQ | 5.3.4 | `mirrors.aliyun.com/apache` |
| Nginx | 1.18.0 | Ubuntu apt |
| FFmpeg | 4.4.2 | Ubuntu apt |
| Tesseract | 4.1.1（chi_sim + eng） | Ubuntu apt |
| yt-dlp | 2026.10.21 | 阿里云 PyPI 镜像 |
| Qdrant | **关闭** | 内存不足，应用降级为本地 cosine + 关键词检索 |

## 2. 端到端验证

| 项 | 结果 |
| --- | --- |
| 测试视频 | 27 秒，3 段画面文字 + TTS 英文语音 |
| 链路 | 上传 → ASR → OCR 抽帧 → Embedding → Planner → Executor → Critic → 结构化结果 |
| 最终状态 | **COMPLETED** |
| 产出 | 带 `[mm:ss]` 时间戳、`ASR` / `OCR` 来源的结论与证据列表 |
| 备注 | TTS 音质导致 "SceneMind" 被识别为 "insane mind"；真实人声应更好 |

验证过程中修复了两个只在真实环境暴露的问题：

1. **FFmpeg 4.4 兼容**：Ubuntu 22.04 自带 ffmpeg 4.4 不识别 `-fps_mode`（5.1+ 才有），OCR 抽帧直接失败。`VideoContextService` 已改为先试 `-fps_mode`、失败自动回退 `-vsync`。
2. **Nginx Basic Auth 与 Bearer Token 冲突**：两者共用 `Authorization` 头。已改为「页面需要 Basic Auth，`/user` `/media` `/analysis` `/admin` `/health` 交给应用自己鉴权」。

## 3. 已知限制

- **内存 1.9GB**：Qdrant 关闭；MySQL / RocketMQ / JVM 均压到低配参数；加 4GB swap 才稳住。**无法做并发/压测**。
- **无 HTTPS**：Let's Encrypt ACME 被网络阻断；公网 80 端口被备案策略拦截，只能用非标准端口 + HTTP。
- **共享公网 IP**：80/443/8088/9090 等端口被平台中间件或其他租户占用，只能依赖服务商端口映射。
- 单账号使用，公开注册已关闭。

## 3.1 本轮加固（2026-11-05）

- **登录双维度限流**：用户名 8 次 / 10 分钟 + 来源 IP 30 次 / 10 分钟，避免撞库与「故意输错密码锁死账号」。
- **每日 AI 配额**：`DAILY_AI_REQUESTS=100`（分析 / 追问 / 证据检索各计一次），补上分钟级限流挡不住的长期消耗。
- **SSE 断线补发**：事件带递增 `id`，前端重连带 `Last-Event-ID`，服务端补发最近 200 条；不再只拿当前快照。
- **trace 采样**：检索诊断只保留前 20 条明细，避免长视频把 Redis 写爆。
- **Agent 预算**：2C 机器实测 120s 不够一次 Critic 重跑，已调整为 `AGENT_MAX_DURATION_MS=600000` / `AGENT_MAX_ESTIMATED_TOKENS=200000`。
- **MCP 远程传输**：已部署 `scenemind-mcp.service`（Node 22，监听 `127.0.0.1:8090`），Nginx 暴露 `/mcp`，Bearer token 鉴权；`POST /mcp` 实测 `tools/list` 返回 7 个工具。

## 4. 如果要做并发测试

最低建议：

| 项 | 建议 |
| --- | --- |
| 服务器 | 4C8G 起（推荐 8C16G），独立公网 IP |
| 向量库 | 重新启用 Qdrant，或换托管向量库 |
| 域名/HTTPS | 已备案域名 + 国内 CDN 证书 |
| 压测 | k6 / JMeter + 一组真实标注测试视频 |
| 成本 | 先设置 `AGENT_MAX_ESTIMATED_COST` 与真实模型单价，防止压测烧穿额度 |
