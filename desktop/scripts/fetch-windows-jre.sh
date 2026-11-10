#!/usr/bin/env bash
# 下载 Temurin 17 Windows x64 JRE 并解压到 desktop/src-tauri/resources/jre。
# 只在目标目录缺少 bin/javaw.exe 时才重新下载/解压；zip 缓存在 ~/.cache/kura-edge。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${KURA_JRE_DEST:-$ROOT/src-tauri/resources/jre}"
CACHE="${KURA_JRE_CACHE:-$HOME/.cache/kura-edge}"
ZIP="$CACHE/temurin17-win-x64-jre.zip"
URL="${KURA_JRE_URL:-https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse}"

if [ -x "$DEST/bin/javaw.exe" ]; then
  echo "[jre] 已存在：$DEST/bin/javaw.exe"
  exit 0
fi

mkdir -p "$CACHE"
if [ ! -f "$ZIP" ]; then
  echo "[jre] 下载 Temurin 17 Windows x64 JRE …"
  curl -fSL --retry 5 --retry-delay 3 -o "$ZIP" "$URL"
fi

echo "[jre] 解压到 $DEST …"
rm -rf "$DEST" "$DEST.tmp"
mkdir -p "$DEST.tmp" "$DEST"
unzip -q "$ZIP" -d "$DEST.tmp"

# Adoptium 包内顶层是 jdk-17.x.y-z-jre/，把内容提升一层，保证 resources/jre/bin/javaw.exe 的固定路径
INNER="$(find "$DEST.tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
if [ -z "$INNER" ]; then
  echo "[jre] 压缩包结构异常：找不到顶层目录" >&2
  exit 1
fi
mv "$INNER"/* "$DEST"/
rm -rf "$DEST.tmp"

# Temurin 里 classes.jsa 等文件是只读（0555），tauri 打包往 target/ 拷资源时 O_TRUNC 会 EACCES
chmod -R u+w "$DEST"

test -x "$DEST/bin/javaw.exe" || {
  echo "[jre] 解压后缺少 bin/javaw.exe，请检查 $ZIP" >&2
  exit 1
}
touch "$DEST/.gitkeep"   # 保留占位文件：resources/jre 必须在仓库里存在，tauri-build 编译期校验
echo "[jre] 就绪：$(du -sh "$DEST" | cut -f1)"
