# NOTICE

## 第三方代码来源

本项目（rikka-agent）大量复用了 **RikkaHub** 的源代码。

- **上游项目**：RikkaHub — <https://github.com/rikkahub/rikkahub>
- **上游许可证**：GNU Affero General Public License v3.0 (AGPL-3.0)
- **复用范围**：`app/`、`ai/`、`common/`、`document/`、`highlight/`、`material3/`、
  `search/`、`speech/`、`web/`、`web-ui/`、`workspace/`、`build-logic/` 等模块的
  绝大部分代码，以及项目的整体架构。
- **基线版本**：v2.4.5（versionCode 172），合并自 `rikkahub/master`。

本仓库以**单一初始提交**形式建立，未保留上游的 Git 提交历史。此文件即为对上游作者
著作权的署名声明。上游完整的提交历史与贡献者名单请见上述仓库地址。

根据 AGPL-3.0 的要求，本项目整体同样以 **AGPL-3.0** 发布，见 [LICENSE](LICENSE)。

## 本项目的修改

在上游基础上新增：

- `workspace/src/main/java/me/rerere/workspace/PersistentShellSession.kt` —— 常驻容器会话
- `workspace/src/main/java/me/rerere/workspace/PersistentProotShellRunner.kt` —— 常驻 runner
- `app/src/main/java/me/rerere/rikkahub/service/ContainerService.kt` —— 容器前台服务
- `tunnel/` —— Cloudflare Tunnel 转发模块（部分代码源自作者自己的 RikkaTunnel 项目，原 MIT 许可，
  作者同意以 AGPL-3.0 再许可）
- `Settings.effectiveJwtEnabled` —— 隧道开启时强制认证的安全约束

## 内置的第三方二进制

- **PRoot**（`workspace/src/main/jniLibs/*/libproot_exec.so`、`libproot_loader.so`）——
  来自 [proot-me/proot](https://github.com/proot-me/proot)，GPL-2.0。
- **cloudflared**（构建时下载至 `tunnel/src/main/jniLibs/*/libcloudflared.so`）——
  来自 [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared)，Apache-2.0。
- **Termux terminal-view** —— 来自 [termux/termux-app](https://github.com/termux/termux-app)，GPL-3.0。
- **material-color-utilities**（git submodule）——
  来自 [material-foundation/material-color-utilities](https://github.com/material-foundation/material-color-utilities)，Apache-2.0。

各二进制与库按其各自上游许可证发布，相关商标归其所有者所有。
