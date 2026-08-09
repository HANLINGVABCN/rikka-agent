# rikka-agent

手机上的 **容器 + agent + AI chat** 一体工具。

一个 Android 应用，同时是：

- **容器** —— PRoot + Ubuntu 24.04 完整发行版，`apt` 可用，打开开关就一直存活
- **AI chat** —— 多提供商 LLM 聊天（OpenAI / Google / Anthropic 及所有 OpenAI 兼容接口）
- **agent** —— chat 可连接容器内的 agent，直接在手机上执行命令
- **远程访问** —— 通过 Cloudflare Tunnel 把 chat 界面暴露到公网

隧道转发只是设置页里的一个小功能，不是主体。

## 状态

**早期开发中。**

| 能力 | 状态 |
|---|---|
| Chat / 提供商管理 / MCP | ✅ 继承自 RikkaHub |
| PRoot 容器 + 终端 | ✅ 继承自 RikkaHub |
| 容器常驻（开关一开就一直活着） | ✅ 已实现，待真机验证 |
| 隧道转发 | 🚧 数据层与进程管理就绪，缺设置页 UI |
| agent 集成 | ⬜ 未开始 |

## 架构

```text
app/          主应用：chat UI、ViewModel、Room、DI、web API 路由
ai/           LLM 提供商抽象、消息模型、流式响应
workspace/    PRoot 容器：rootfs 安装、shell 执行、常驻会话
tunnel/       Cloudflare Tunnel：连接器进程管理、API 客户端
web/          Ktor 服务器 + 内置 React SPA
search/       多个搜索后端
speech/       TTS 与 ASR
document/     PDF/DOCX/PPTX/EPUB 解析
highlight/    语法高亮
common/       通用工具
material3/    Material 配色工具
```

### 容器为什么能跑

Android 10+ 禁止执行可写数据目录里的二进制。PRoot 本体伪装成 `libproot_exec.so`
放进 `jniLibs`，系统安装时把它解到只读的 `nativeLibraryDir` —— 那里允许执行。
rootfs 内的 ELF 由 PRoot 的 loader 加载，不走对数据目录文件的直接 `execve`，
因此不触发 SELinux 的 W^X 限制。cloudflared 用的是同一个手法。

这也意味着**不需要降低 targetSdk**，本项目在 targetSdk 37 下运行。

## 安全

⚠️ **PRoot 不是安全沙箱。** 它是 ptrace 路径重写，提供 chroot 式的文件系统视图和
假 root，但不限制系统调用、网络或进程创建。容器内的进程以应用的 UID 和权限运行，
拥有完整的出网能力。

⚠️ **隧道把 chat 暴露到公网时，认证是强制的。** 远程会话中 `workspace_shell` 自动批准，
意味着认证是唯一一道闸 —— 因此 `tunnelEnabled` 为真时 JWT 无条件启用，不提供关闭开关
（见 `Settings.effectiveJwtEnabled`）。未设置访问密码时所有路由拒绝访问（fail-closed）。

## 构建

需要 JDK 17 与 Android SDK。

```bash
git clone --recursive <repo-url>   # material-color-utilities 是 submodule，必须递归
cd rikka-agent
bash scripts/download-cloudflared.sh   # 下载 cloudflared 到 tunnel/src/main/jniLibs
cd web-ui && pnpm install --frozen-lockfile && cd ..
./gradlew assembleDebug
```

Firebase 需要 `app/google-services.json`。CI 会生成占位文件；本地构建可以自己放一份，
或参考 `.github/workflows/ci.yml` 里的占位内容。

## 许可证

**AGPL-3.0** —— 见 [LICENSE](LICENSE)。

本项目大量复用 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0）的代码，
署名与第三方组件清单见 [NOTICE.md](NOTICE.md)。

注意 AGPL 第 13 条：通过网络提供本软件的服务时，必须向使用者提供完整源码获取途径。
如果你启用隧道把 chat 暴露给他人使用，这条对你适用。
