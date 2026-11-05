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

# 用 bash 自身的 globstar 收集源文件，不依赖外部 find / xargs：
# Windows 的 PATH 里 find.exe 是文本搜索工具，会让 `find -print0 | xargs` 直接失败。
shopt -s globstar nullglob
sources=("$CORE_DIR"/**/*.java)
if (( ${#sources[@]} == 0 )); then
  echo "no Java sources found under $CORE_DIR" >&2
  exit 1
fi
mkdir -p "$OUT_DIR"
javac --release 21 -encoding UTF-8 -d "$OUT_DIR" "${sources[@]}"

java -cp "$OUT_DIR" com.scenemind.core.eval.SceneMindBenchmark \
  --out "$ROOT/docs/benchmark-report.md"
