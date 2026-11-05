#!/usr/bin/env bash
# SceneMind 离线评测：零 Maven / 零 Spring / 零外部服务，只需要一个 JDK 21+。
# 直接编译 core 纯函数内核并运行自带断言的评测入口，结果写入 docs/benchmark-report.md。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_DIR="$ROOT/server/src/main/java/com/scenemind/core"
OUT_DIR="$ROOT/server/target/scenemind-core-classes"

if ! command -v javac >/dev/null 2>&1; then
  echo "javac not found; JDK 21+ is required" >&2
  exit 1
fi

# 输出到已被 .gitignore 忽略的构建目录，避免污染仓库，也避免跨平台临时目录清理差异。
mkdir -p "$OUT_DIR"
find "$CORE_DIR" -name '*.java' -print0 | xargs -0 javac --release 21 -encoding UTF-8 -d "$OUT_DIR"

java -cp "$OUT_DIR" com.scenemind.core.eval.SceneMindBenchmark \
  --out "$ROOT/docs/benchmark-report.md"
