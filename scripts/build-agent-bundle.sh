#!/usr/bin/env bash
# 构建内置的 pi agent 运行时, 输出到 app/src/main/assets/agent/。
#
# 产物是一个自包含的 tar.gz: Node 运行时 + pi 及其全部依赖。首次绑定工作区时
# 由 app 解压进容器, 用户不需要联网、不需要 npm、不需要等待。
#
# 之所以在 CI 构建而不是让 app 运行时 npm install: 手机上装 npm 包又慢又容易失败,
# 而且 proot 里的网络/DNS 本身就不稳。预构建一次, 所有用户直接用。
set -euo pipefail

NODE_VERSION="${NODE_VERSION:-22.14.0}"
PI_VERSION="${PI_VERSION:-latest}"
ARCH="${AGENT_ARCH:-arm64}"          # arm64 | x64
OUT_DIR="app/src/main/assets/agent"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "[build-agent] $*"; }

# ---------------------------------------------------------------- Node 运行时
log "下载 Node $NODE_VERSION ($ARCH)..."
node_tarball="node-v${NODE_VERSION}-linux-${ARCH}.tar.xz"
curl --fail --location --retry 3 --retry-all-errors \
  "https://nodejs.org/dist/v${NODE_VERSION}/${node_tarball}" \
  --output "$WORK/node.tar.xz"

mkdir -p "$WORK/runtime"
tar -xJf "$WORK/node.tar.xz" -C "$WORK/runtime" --strip-components=1

# 砍掉运行 pi 用不到的部分, 能省 ~40MB
rm -rf "$WORK/runtime/include" \
       "$WORK/runtime/share" \
       "$WORK/runtime/lib/node_modules/npm/docs" \
       "$WORK/runtime/lib/node_modules/corepack"

# ---------------------------------------------------------------- pi 及依赖
log "安装 pi ($PI_VERSION)..."
mkdir -p "$WORK/pi"
cd "$WORK/pi"
npm init -y >/dev/null 2>&1
# --ignore-scripts: pi 文档明确说正常安装不需要生命周期脚本; 也避免在 CI 上跑第三方脚本
npm install --ignore-scripts --omit=dev --silent \
  "@earendil-works/pi-coding-agent@${PI_VERSION}"
cd - >/dev/null

# pi 的可执行入口, 供容器内直接调用
mkdir -p "$WORK/runtime/pi"
cp -r "$WORK/pi/node_modules" "$WORK/runtime/pi/node_modules"

cat > "$WORK/runtime/pi/pi" <<'LAUNCHER'
#!/bin/sh
# pi 启动器: 用内置 Node 跑内置 pi, 不依赖容器里装了什么
here="$(cd "$(dirname "$0")" && pwd)"
exec "$here/../bin/node" \
  "$here/node_modules/@earendil-works/pi-coding-agent/dist/cli.js" "$@"
LAUNCHER
chmod +x "$WORK/runtime/pi/pi"

# ---------------------------------------------------------------- 打包
mkdir -p "$OUT_DIR"
out="$OUT_DIR/agent-${ARCH}.tar.gz"
log "打包 -> $out"
tar -czf "$out" -C "$WORK" runtime

size_mb=$(( $(stat --format='%s' "$out") / 1024 / 1024 ))
log "完成: ${size_mb}MB"

# 版本号随包走, app 靠它判断要不要重新解压
printf '%s' "node-${NODE_VERSION}+pi-$(node -e "
  const p = require('$WORK/pi/node_modules/@earendil-works/pi-coding-agent/package.json');
  process.stdout.write(p.version);
" 2>/dev/null || echo unknown)" > "$OUT_DIR/agent-version.txt"
log "版本: $(cat "$OUT_DIR/agent-version.txt")"
