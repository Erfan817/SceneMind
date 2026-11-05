#!/usr/bin/env bash
#
# 服务器侧一键部署：安装 Docker（如缺）→ 校验 .env → 构建并启动全栈。
# 用法：bash scripts/deploy.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "未检测到 Docker，开始安装（需要 root 或 sudo）..." >&2
  if command -v sudo >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "${USER:-root}" || true
  else
    curl -fsSL https://get.docker.com | sh
  fi
  echo "Docker 已安装。若当前用户不在 docker 组，请重新登录（或 newgrp docker）后重跑。" >&2
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "缺少 docker compose v2 插件，请先安装。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.prod.example .env
  echo "已生成 .env。请先填写 SITE_ADDRESS、BASIC_AUTH_HASH、各密码与 SILICONFLOW_API_KEY，然后重跑。" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

missing=()
for variable in MYSQL_ROOT_PASSWORD DB_PASSWORD REDIS_PASSWORD MINIO_ACCESS_KEY \
                 MINIO_SECRET_KEY QDRANT_API_KEY SILICONFLOW_API_KEY \
                 SITE_ADDRESS BASIC_AUTH_USER BASIC_AUTH_HASH; do
  value="${!variable:-}"
  if [[ -z "$value" || "$value" == change-* ]]; then
    missing+=("$variable")
  fi
done
if (( ${#missing[@]} > 0 )); then
  echo "以下变量仍为空或示例值，请先编辑 .env：" >&2
  printf '  - %s\n' "${missing[@]}" >&2
  exit 1
fi

echo "构建并启动（首次构建需要几分钟）..."
COMPOSE_ARGS=(-f docker-compose.prod.yml)
mem_total_mb="$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 4096)"
if (( mem_total_mb < 3800 )); then
  echo "检测到内存 ${mem_total_mb}MB < 3800MB，启用低配覆盖（关闭 Qdrant、压缩各服务内存）。" >&2
  COMPOSE_ARGS+=(-f deploy/compose.lowmem.override.yml)
fi

docker compose "${COMPOSE_ARGS[@]}" --env-file .env up -d --build

echo "等待后端健康检查..."
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${SERVER_PORT:-9090}/health" >/dev/null 2>&1; then
    echo "后端已就绪：http://127.0.0.1:${SERVER_PORT:-9090}/health"
    break
  fi
  sleep 5
done

docker compose "${COMPOSE_ARGS[@]}" --env-file .env ps
echo
echo "站点地址：${SITE_ADDRESS}"
echo "首次使用：打开站点 → 注册账号 → 按 docs/DEPLOY.md 提权为 ADMIN →"
echo "把 AUTH_REGISTRATION_ENABLED 改成 false，再重跑本脚本关闭公开注册。"
