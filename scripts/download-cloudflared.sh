#!/usr/bin/env bash
# 下载官方 cloudflared 并按 ABI 放进 :tunnel 的 jniLibs。
#
# 伪装成 .so 是为了让系统把它解到只读的 nativeLibraryDir —— Android 10+ 禁止执行
# 可写数据目录里的二进制, 这是唯一能跑自带原生程序的合规位置。
set -euo pipefail

repo="cloudflare/cloudflared"
version="${CLOUDFLARED_VERSION:-}"

if [[ -z "$version" ]]; then
  headers=()
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    headers=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi
  version="$(
    curl --fail --silent --show-error --location "${headers[@]}" \
      "https://api.github.com/repos/$repo/releases/latest" | jq -er '.tag_name'
  )"
fi

base_url="https://github.com/$repo/releases/download/$version"
destination="tunnel/src/main/jniLibs"

download_binary() {
  local abi="$1"
  local artifact="$2"
  local target="$destination/$abi/libcloudflared.so"

  mkdir -p "$(dirname "$target")"
  curl --fail --location --retry 3 --retry-all-errors \
    "$base_url/$artifact" --output "$target"

  # 下载失败时 GitHub 可能返回一个几百字节的错误页, 那种文件存在但一跑就废 ——
  # 这里卡一道大小检查, 免得等到运行时才发现
  local size
  size="$(stat --format='%s' "$target")"
  if (( size < 1000000 )); then
    echo "Downloaded cloudflared binary is unexpectedly small: $target ($size bytes)" >&2
    exit 1
  fi
  file "$target"
}

echo "Bundling cloudflared $version"
# 只下 app 实际打包的两个 ABI(见 app/build.gradle.kts 的 abiFilters/splits) ——
# :workspace 的 proot 也只有这两个, 多下的架构会被打包工具丢弃, 白费带宽
download_binary "arm64-v8a" "cloudflared-linux-arm64"
download_binary "x86_64" "cloudflared-linux-amd64"
