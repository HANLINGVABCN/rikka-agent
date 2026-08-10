#!/bin/bash
# 在容器里部署 pi agent。
#
# 这个脚本在 proot 容器内运行, 不是在 Android 侧。幂等 —— 重复跑只补齐缺失的部分。
#
# 关键: 所有 apt 操作必须完全非交互。proot 里没有可用的 stdin, 一旦某个包弹出配置
# 提示(时区、服务重启确认之类), 进程会永远等输入, 表现就是"部署中"卡死不动。
set -euo pipefail

NODE_MAJOR=22
PI_PACKAGE="@earendil-works/pi-coding-agent"

export DEBIAN_FRONTEND=noninteractive
export DEBCONF_NONINTERACTIVE_SEEN=true
export NEEDRESTART_MODE=a
export TERM=dumb
# npm 在非 TTY 下仍可能画进度条, 关掉省得输出里全是控制字符
export NPM_CONFIG_PROGRESS=false
export NPM_CONFIG_FUND=false
export NPM_CONFIG_AUDIT=false

log() { echo "[deploy-pi] $*"; }

# apt 一律走这个包装: 非交互 + 强制用维护者版配置文件 + stdin 接 /dev/null
apt_run() {
  apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "$@" </dev/null
}

# Ubuntu Base 24.04 是最小镜像, curl/ca-certificates 都没有
ensure_base() {
  if command -v curl >/dev/null 2>&1 && [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    log "基础依赖已就绪"
    return
  fi
  log "更新软件包索引(首次较慢)..."
  apt_run update
  log "安装 curl / ca-certificates ..."
  apt_run install curl ca-certificates xz-utils
  log "基础依赖安装完成"
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
  log "配置 NodeSource 源..."
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" -o /tmp/nodesource_setup.sh
  bash /tmp/nodesource_setup.sh
  log "安装 Node $NODE_MAJOR (约 30MB, 请耐心)..."
  apt_run install nodejs
  log "Node $(node -v) 安装完成"
}

ensure_pi() {
  if command -v pi >/dev/null 2>&1; then
    log "pi 已安装: $(pi --version 2>/dev/null || echo '?')"
    return
  fi
  log "安装 $PI_PACKAGE (约 20MB)..."
  # --ignore-scripts: pi 官方文档明确说正常安装不需要生命周期脚本, 关掉更快也更安全
  npm install -g --ignore-scripts "$PI_PACKAGE" </dev/null
  log "pi 安装完成: $(pi --version 2>/dev/null || echo '?')"
}

ensure_base
ensure_node
ensure_pi

log "部署完成"
