# 部署到云服务器

> 目标：把 SceneMind 从「本地跑」变成「公网可访问的实例」。单机 Docker Compose，前后端同源，Caddy 自动 HTTPS。
>
> 如果服务器在国内且无法访问 Docker Hub / GitHub / Let's Encrypt（小带宽 VPS 常见），用裸机方案：[`DEPLOY_BARE.md`](DEPLOY_BARE.md)。

## 0. 服务器要求

| 项 | 最低 | 建议 |
| --- | --- | --- |
| CPU / 内存 | 2C / 4GB | 4C / 8GB |
| 磁盘 | 50GB | 100GB（视频文件占空间） |
| 系统 | Ubuntu 22.04+ / Debian 12+ | 同左 |
| 网络 | 开放 80、443 | 同左 |

> 2C4G 能跑起来，但 MySQL + Redis + Qdrant + MinIO + RocketMQ（两个进程）+ JVM 会吃满内存，建议加 2GB swap：
> `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`

## 1. 准备访问地址

- **有域名**：把域名 A 记录指向服务器 IP，`SITE_ADDRESS` 填域名，Caddy 自动签发证书。
- **只有 IP**：用 `sslip.io` 的免费泛解析，`SITE_ADDRESS=1.2.3.4.sslip.io`（把 IP 换成你的），同样能拿证书。
- **只想先用 HTTP**：`SITE_ADDRESS=http://1.2.3.4`。

## 2. 拉代码并配置

```bash
git clone https://github.com/Erfan817/SceneMind.git
cd SceneMind
cp .env.prod.example .env
```

编辑 `.env`，至少填这些：

```bash
SITE_ADDRESS=你的域名或 IP.sslip.io
BASIC_AUTH_USER=demo
BASIC_AUTH_HASH=...        # 见下方生成方式
MYSQL_ROOT_PASSWORD=...
DB_PASSWORD=...
REDIS_PASSWORD=...
MINIO_ACCESS_KEY=...
MINIO_SECRET_KEY=...
QDRANT_API_KEY=...
SILICONFLOW_API_KEY=...    # 真实 Key
CORS_ALLOWED_ORIGINS=https://你的域名
```

生成站点访问密码的 bcrypt 哈希：

```bash
docker run --rm caddy:2.8-alpine caddy hash-password --plaintext '你的密码'
```

> 输出形如 `$2a$14$...`，**在 `.env` 里必须用单引号包起来**：`BASIC_AUTH_HASH='$2a$14$...'`。不加引号会被 shell 当成位置参数展开，导致密码校验失败。

> 站点默认开启 Basic Auth：陌生人即使知道地址也进不来，避免被刷 API 额度。想公开访问，删掉 `deploy/Caddyfile` 里的 `basic_auth` 块并重启 `web`。

## 3. 一键启动

```bash
bash scripts/deploy.sh
```

脚本会：安装 Docker（如缺）→ 校验 `.env` → 构建后端镜像（含 FFmpeg / Tesseract / yt-dlp）→ 构建前端 → 启动 8 个服务 → 等 `/health` 就绪。

首次构建约 5–10 分钟（Maven 依赖 + npm 依赖）。

## 4. 创建账号并提权

1. 打开 `https://你的地址`，用 Basic Auth 登录后注册自己的账号；
2. 在服务器上把自己提成管理员（管理台/失败任务重投需要）：

```bash
docker compose -f docker-compose.prod.yml exec mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" media_db \
  -e "UPDATE users SET role='ADMIN' WHERE username='你的账号';"
```

3. 编辑 `.env`：`AUTH_REGISTRATION_ENABLED=false`，再跑一次 `bash scripts/deploy.sh` 关闭公开注册。

## 5. 验证

```bash
# 后端健康
curl -fsS http://127.0.0.1:9090/health

# 端到端冒烟（在服务器上，需要 .env 里的账号密码与一个可分析视频链接）
SCENEMIND_SMOKE_URL='https://www.bilibili.com/video/BVxxxx' \
SCENEMIND_SMOKE_EMAIL='你的账号' SCENEMIND_SMOKE_PASSWORD='你的密码' \
bash scripts/smoke-test.sh
```

浏览器打开站点，上传一个短视频或粘贴链接，应能看到阶段进度、带时间戳的证据、音画冲突提示（如果视频里存在）。

## 6. 成本与安全（公网必看）

| 项 | 做法 |
| --- | --- |
| LLM / ASR 费用 | `.env` 里填真实单价并设置 `AGENT_MAX_ESTIMATED_COST`（建议 1–5 元/任务）；关闭公开注册，只给自己的账号用 |
| 站点访问 | 保留 Caddy Basic Auth；或换成 Cloudflare Access 等 |
| 端口 | 只暴露 80/443；MySQL / Redis / Qdrant / MinIO 控制台都不映射到公网 |
| 密钥 | `.env` 不入库（已在 `.gitignore`）；不要提交到 GitHub |
| 备份 | 见下方 |

## 7. 备份与更新

```bash
# MySQL 备份
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" media_db > backup-$(date +%F).sql

# 更新代码并重建
git pull
bash scripts/deploy.sh
```

MinIO 视频与 Qdrant 向量在 Docker 命名卷里，随卷备份即可（`docker run --rm -v scenemind_minio-data:/data -v $PWD:/backup alpine tar czf /backup/minio.tgz -C /data .`）。

## 8. 已知限制

- 单机部署，没有多租户与横向扩容；
- 没有独立的 SSE 断线补发（重连会取当前快照）；
- MCP Server 目前只支持 stdio，远程 Agent 接入需要再补 Streamable HTTP + token（见 `docs/MCP_SERVER.md`）；
- 首次启动后 Flyway 会自动建表，无需手工执行 SQL。
