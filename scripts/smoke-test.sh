#!/usr/bin/env bash
#
# SceneMind 端到端冒烟：健康检查 → 登录 → 提交 URL 分析 → 轮询状态 → 断言结果带时间戳证据。
#
# 这是**本地**验证脚本，不进 CI：它需要真实中间件、真实 SILICONFLOW_API_KEY，
# 以及一个可被 yt-dlp 下载的公开视频链接。CI 里只跑零依赖的单元测试与离线评测。
#
# 用法（Windows 用 Git Bash / WSL 执行）：
#   bash scripts/smoke-test.sh
#
# 可选环境变量（写进 .env 或直接在命令行导出）：
#   SCENEMIND_SMOKE_URL              要分析的视频链接（必填，缺失则跳过）
#   SCENEMIND_SMOKE_GOAL             分析目标，默认要求带时间戳证据
#   SCENEMIND_SMOKE_EMAIL            登录账号，与 PASSWORD 成对使用（缺失则跳过）
#   SCENEMIND_SMOKE_PASSWORD         登录密码
#   SCENEMIND_SMOKE_BASE_URL         后端地址，默认 http://127.0.0.1:${SERVER_PORT:-9090}
#   SCENEMIND_SMOKE_TIMEOUT          轮询总超时（秒），默认 900
#   SCENEMIND_SMOKE_INTERVAL         轮询间隔（秒），默认 10
#   SCENEMIND_SMOKE_UPLOAD_TIMEOUT   提交链接（下载 + 入库）超时（秒），默认 600
#
# 退出码：0 = 通过或按设计跳过；非 0 = 环境缺失或断言失败。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command in curl node; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "缺少命令：$command（curl 用于请求后端，node 用于解析 JSON）" >&2
    exit 1
  }
done

# 后端接口是 POST/GET + query 参数，中文目标必须编码后再拼进 URL。
urlencode() {
  node -e 'process.stdout.write(encodeURIComponent(process.argv[1]))' "$1"
}

# 从 stdin 读 JSON，按点号路径取值；字段缺失输出空串，非法 JSON 直接报错退出。
json_get() {
  node -e '
    let raw = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => { raw += chunk; });
    process.stdin.on("end", () => {
      let value;
      try {
        value = JSON.parse(raw);
      } catch (error) {
        process.stderr.write("响应不是合法 JSON：" + error.message + "\n");
        process.exit(2);
      }
      for (const key of process.argv[1].split(".")) {
        if (value === null || value === undefined) break;
        value = value[key];
      }
      if (value === null || value === undefined) return;
      process.stdout.write(typeof value === "string" ? value : JSON.stringify(value));
    });
  ' "$1"
}

# 前置条件不满足时明确跳过，并说明怎么开启；跳过不算失败。
skip() {
  echo
  echo "跳过冒烟：$1"
  echo "$2"
  exit 0
}

if [[ ! -f .env ]]; then
  echo ".env 不存在。先运行 bash scripts/dev-up.sh 生成 .env 并启动中间件，再按提示填入真实值。" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

BASE_URL="${SCENEMIND_SMOKE_BASE_URL:-http://127.0.0.1:${SERVER_PORT:-9090}}"
GOAL="${SCENEMIND_SMOKE_GOAL:-提炼视频核心结论，并为每条结论标注时间戳证据}"
TIMEOUT="${SCENEMIND_SMOKE_TIMEOUT:-900}"
INTERVAL="${SCENEMIND_SMOKE_INTERVAL:-10}"
UPLOAD_TIMEOUT="${SCENEMIND_SMOKE_UPLOAD_TIMEOUT:-600}"

echo "== 1/4 健康检查 ${BASE_URL}/health"
health="$(curl --fail --silent --show-error --max-time 10 "${BASE_URL}/health" || true)"
if [[ -z "$health" ]]; then
  echo "后端没有响应：${BASE_URL}/health" >&2
  echo "先启动后端：set -a; source .env; set +a; cd server && bash ./mvnw spring-boot:run" >&2
  exit 1
fi
health_code="$(printf '%s' "$health" | json_get code || true)"
if [[ "$health_code" != "0" ]]; then
  echo "健康检查未通过：$health" >&2
  exit 1
fi
echo "   后端就绪：$(printf '%s' "$health" | json_get data || true)"

# 分析要真实调用 LLM / Embedding / ASR，没有 Key 就只能跳过，不要伪装成通过。
if [[ -z "${SILICONFLOW_API_KEY:-}" || "${SILICONFLOW_API_KEY}" == change-* ]]; then
  skip "没有可用的 SILICONFLOW_API_KEY" \
    "在 .env 里填入真实 Key（SILICONFLOW_API_KEY=sk-...），然后重跑 bash scripts/dev-up.sh 与 bash scripts/smoke-test.sh。"
fi

if [[ -z "${SCENEMIND_SMOKE_EMAIL:-}" || -z "${SCENEMIND_SMOKE_PASSWORD:-}" ]]; then
  skip "没有冒烟账号" \
    "先在工作台注册一个账号（POST /user/register），再在 .env 里加两行：
  SCENEMIND_SMOKE_EMAIL=<账号>
  SCENEMIND_SMOKE_PASSWORD=<密码>"
fi

if [[ -z "${SCENEMIND_SMOKE_URL:-}" ]]; then
  skip "没有设置 SCENEMIND_SMOKE_URL" \
    "在 .env 里加一行 SCENEMIND_SMOKE_URL=<可分析的视频链接>（公开的 B 站 / YouTube 链接或直链）。
  需要登录态的站点请同时配置 YTDLP_COOKIES。"
fi

echo "== 2/4 登录 ${SCENEMIND_SMOKE_EMAIL}"
login_body="$(node -e '
  process.stdout.write(JSON.stringify({
    username: process.argv[1],
    password: process.argv[2]
  }));
' "$SCENEMIND_SMOKE_EMAIL" "$SCENEMIND_SMOKE_PASSWORD")"
login_response="$(curl --silent --show-error --max-time 20 \
  --header "Content-Type: application/json" \
  --data "$login_body" \
  "${BASE_URL}/user/login" || true)"
token="$(printf '%s' "$login_response" | json_get data.token || true)"
if [[ -z "$token" ]]; then
  echo "登录失败：${login_response:-无响应}" >&2
  exit 1
fi
echo "   已拿到会话令牌"

echo "== 3/4 提交 URL 分析（下载 + 入库最长等待 ${UPLOAD_TIMEOUT}s）"
media_response="$(curl --silent --show-error --max-time "$UPLOAD_TIMEOUT" \
  --header "Authorization: Bearer ${token}" \
  --request POST \
  "${BASE_URL}/media/upload-url?url=$(urlencode "${SCENEMIND_SMOKE_URL}")" || true)"
media_id="$(printf '%s' "$media_response" | json_get data.id || true)"
if [[ -z "$media_id" ]]; then
  echo "链接入库失败：${media_response:-无响应}" >&2
  echo "常见原因：yt-dlp 下载失败（需要登录态的视频请配 YTDLP_COOKIES）、链接不是视频、MinIO 未就绪。" >&2
  exit 1
fi
echo "   mediaId=${media_id}"

submit_response="$(curl --silent --show-error --max-time 30 \
  --header "Authorization: Bearer ${token}" \
  --request POST \
  "${BASE_URL}/analysis/ai?id=${media_id}&goal=$(urlencode "$GOAL")" || true)"
submit_code="$(printf '%s' "$submit_response" | json_get code || true)"
# 40900 = 相同视频与分析目标正在处理中，属于可继续轮询的状态，不算失败。
if [[ "$submit_code" != "0" && "$submit_code" != "40900" ]]; then
  echo "提交分析失败：${submit_response:-无响应}" >&2
  exit 1
fi

echo "== 4/4 轮询 /analysis/analysis-status（超时 ${TIMEOUT}s，间隔 ${INTERVAL}s）"
deadline=$(( $(date +%s) + TIMEOUT ))
status_response=""
state=""
while :; do
  status_response="$(curl --silent --show-error --max-time 30 \
    --header "Authorization: Bearer ${token}" \
    "${BASE_URL}/analysis/analysis-status?id=${media_id}&goal=$(urlencode "$GOAL")" || true)"
  state="$(printf '%s' "$status_response" | json_get data.state || true)"
  message="$(printf '%s' "$status_response" | json_get data.message || true)"
  echo "   [$(date +%H:%M:%S)] state=${state:-未知} ${message}"

  case "$state" in
    COMPLETED) break ;;
    FAILED)
      echo "分析失败：${message:-无错误信息}" >&2
      exit 1
      ;;
  esac

  if (( $(date +%s) >= deadline )); then
    echo "等待超时（${TIMEOUT}s）：最后一次 state=${state:-未知}" >&2
    echo "长视频可以调大 SCENEMIND_SMOKE_TIMEOUT 后重试。" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done

result="$(printf '%s' "$status_response" | json_get data.result || true)"
if [[ -z "$result" ]]; then
  echo "状态为 COMPLETED，但 result 为空。" >&2
  exit 1
fi

timestamp_count="$(printf '%s' "$result" | grep -oE '\[[0-9]{2}:[0-9]{2}\]' | wc -l | tr -d ' ' || true)"
if [[ "${timestamp_count:-0}" -eq 0 ]]; then
  echo "结果里没有 [mm:ss] 时间戳证据，前 20 行如下：" >&2
  printf '%s\n' "$result" | head -n 20 >&2
  exit 1
fi

conflicts="$(printf '%s' "$status_response" | json_get data.conflicts || true)"
echo
echo "冒烟通过：mediaId=${media_id}，结果含 ${timestamp_count} 处 [mm:ss] 时间戳证据。"
if [[ -n "$conflicts" && "$conflicts" != "[]" ]]; then
  echo "本次结果同时带音画冲突标注：$conflicts"
fi
echo "在 http://localhost:5173 的视频列表里打开该任务即可回放证据。"
