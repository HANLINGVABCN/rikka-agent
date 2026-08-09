#!/bin/bash
# 在容器里部署 pi agent。
#
# 这个脚本在 proot 容器内运行(通过 workspace_shell 或常驻会话), 不是在 Android 侧。
# 幂等 —— 重复跑只会补齐缺失的部分。
set -euo pipefail

NODE_MAJOR=22
PI_PACKAGE="@earendil-works/pi-coding-agent"

log() { echo "[deploy-pi] $*"; }

# Ubuntu Base 24.04 是最小镜像, curl/ca-certificates 都没有
ensure_base() {
  if ! command -v curl >/dev/null 2>&1; then
    log "安装基础依赖..."
    apt-get update -qq
    apt-get install -y -qq curl ca-certificates xz-utils >/dev/null
  fi
}

# 走 NodeSource 而不是 apt 自带的 —— Ubuntu 24.04 的 nodejs 是 18, pi 要 >=22
ensure_node() {
  if command -v node >/dev/null 2>&1; then
    local current
    current="$(node -v | sed 's/^v//; s/\..*//')"
    if [ "$current" -ge "$NODE_MAJOR" ]; then
      log "Node $(node -v) 已就绪"
      return
    fi
    log "Node $(node -v) 版本过低, 需要 >= $NODE_MAJOR"
  fi
  log "安装 Node $NODE_MAJOR..."
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - >/dev/null 2>&1
  apt-get install -y -qq nodejs >/dev/null
  log "Node $(node -v) 安装完成"
}

ensure_pi() {
  if command -v pi >/dev/null 2>&1; then
    log "pi 已安装: $(pi --version 2>/dev/null || echo '?')"
    return
  fi
  log "安装 $PI_PACKAGE ..."
  npm install -g --silent "$PI_PACKAGE"
  log "pi 安装完成: $(pi --version 2>/dev/null || echo '?')"
}

ensure_base
ensure_node
ensure_pi

log "部署完成。用法: pi --help"
