# 裸机部署（国内受限网络备用方案）

> 适用场景：服务器无法访问 Docker Hub / GitHub / Let's Encrypt（国内小带宽 VPS 常见）。
> Docker Compose 方案见 [`DEPLOY.md`](DEPLOY.md)；本文件记录不依赖 Docker 的等价部署方式。
> 实际部署环境与端到端验证记录见 [`DEPLOY_STATUS.md`](DEPLOY_STATUS.md)。

> 环境实测补充：
> - Ubuntu 22.04 自带 **FFmpeg 4.4** 不识别 `-fps_mode`（5.1+ 才有）；`VideoContextService` 已内置自动回退 `-vsync`。
> - 硅基流动的 ASR 模型建议用 `Qwen/Qwen3-ASR-1.7B`（实测比 `TeleAI/TeleSpeechASR` 准）；模型列表会变，部署前先 `GET /v1/models` 确认。
> - Nginx 若同时启用 Basic Auth与应用 Bearer Token，必须给 API 路径加 `auth_basic off;`，否则两者抢同一个 `Authorization` 头。

## 1. 与 Docker 方案的差异

| 组件 | Docker 方案 | 裸机方案 |
| --- | --- | --- |
| 运行时 | 容器 | 宿主 systemd |
| MySQL / Redis | 容器 | `apt install mysql-server redis-server` |
| MinIO | quay.io 镜像 | 官方中国 CDN 二进制 `dl.minio.org.cn` |
| RocketMQ | 镜像 | 阿里云 Apache 镜像的二进制包 |
| Qdrant | 容器 | **关闭**（`QDRANT_ENABLED=false`，应用降级到本地向量） |
| 前端 | Caddy 镜像内构建 | 本地 `npm run build` 后上传 `dist/` |
| 反向代理 | Caddy + 自动 HTTPS | Nginx + Basic Auth（无公网证书） |

## 2. 组件与端口

| 服务 | 监听 | systemd 单元 |
| --- | --- | --- |
| SceneMind 后端 | `127.0.0.1:9090` | `scenemind.service` |
| Nginx（前端 + 反代） | `0.0.0.0:8088` | `nginx.service` |
| MySQL 8 | `127.0.0.1:3306` | `mysql.service` |
| Redis 6 | `127.0.0.1:6379` | `redis-server.service` |
| MinIO | `127.0.0.1:9000`（控制台 9001） | `minio.service` |
| RocketMQ NameServer | `0.0.0.0:9876` | `rocketmq-namesrv.service` |
| RocketMQ Broker | `0.0.0.0:10911` | `rocketmq-broker.service` |

> 云厂商若对 80/443 做备案拦截，Nginx 改用 8088 等非标准端口；同时在服务商面板做「外部端口 → 内部 8088」的端口映射。

## 3. 部署步骤（摘要）

1. **本地构建**
   ```bash
   cd server && bash ./mvnw -DskipTests package     # target/server-*.jar
   cd ../client && VITE_BASE=/ npm run build        # dist/
   ```
2. **上传**
   ```bash
   scp server/target/server-*.jar root@HOST:/opt/scenemind/app.jar
   scp -r client/dist/* root@HOST:/opt/scenemind/web/
   ```
3. **宿主依赖**
   ```bash
   apt-get install -y mysql-server redis-server nginx ffmpeg tesseract-ocr \
     tesseract-ocr-chi-sim tesseract-ocr-eng unzip
   ```
4. **中间件**
   - MySQL：建库 `media_db` 与应用账号；
   - Redis：`requirepass` + 只绑 `127.0.0.1`；
   - MinIO：下载 `https://dl.minio.org.cn/server/minio/release/linux-amd64/minio`，systemd 启动；
   - RocketMQ：下载 `https://mirrors.aliyun.com/apache/rocketmq/5.3.4/rocketmq-all-5.3.4-bin-release.zip`，`brokerIP1 = 127.0.0.1`，JVM 压到 `-Xms192m -Xmx192m`。
5. **后端服务**：`/opt/scenemind/.env` 提供环境变量，systemd 用 `EnvironmentFile` 注入；`QDRANT_ENABLED=false`。
6. **Nginx**：`/opt/scenemind/web` 作 root，`try_files ... /index.html` 支持 SPA；`/user /media /analysis /admin /health` 反代到 `127.0.0.1:9090`；SSE 需要 `proxy_buffering off`；`client_max_body_size 2048m`。

## 4. 更新发布

```bash
# 本地重新构建后：
scp server/target/server-*.jar root@HOST:/opt/scenemind/app.jar
scp -r client/dist/* root@HOST:/opt/scenemind/web/
ssh root@HOST 'systemctl restart scenemind && systemctl reload nginx'
```

## 5. 已知限制

- **无公网 HTTPS**：Let's Encrypt ACME 被网络阻断，只能用 HTTP + Basic Auth；要 HTTPS 需要域名备案 + 国内 CDN 证书，或改用有外网出口的服务器。
- **Qdrant 关闭**：语义通道退化为本地 cosine + 关键词，功能可用但大视频检索质量不如完整部署。
- **单机、无多租户**；内存 < 2GB 时必须配 swap（本机 4GB swap）。
- `yt-dlp` 通过 `pip3 install -i https://mirrors.aliyun.com/pypi/simple/ yt-dlp` 安装；若失败，URL 视频不可用，本地文件上传不受影响。
