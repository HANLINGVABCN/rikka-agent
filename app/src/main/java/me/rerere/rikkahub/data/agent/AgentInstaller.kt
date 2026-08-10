package me.rerere.rikkahub.data.agent

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import java.io.File

private const val TAG = "AgentInstaller"

/** pi agent 在容器里的安装状态 */
sealed interface AgentInstallState {
    data object NotInstalled : AgentInstallState
    data class Installing(val message: String) : AgentInstallState
    data class Ready(val version: String) : AgentInstallState
    data class Failed(val message: String) : AgentInstallState
}

/**
 * 把内置的 pi agent 解压进容器。
 *
 * Node 运行时和 pi 及其全部依赖都随 APK 打包(见 scripts/build-agent-bundle.sh),
 * 这里只做解压 —— 不联网、不 npm install。手机上装 npm 包又慢又容易失败,
 * proot 里的 DNS 本身也不稳, 预构建一次让所有人直接用是更可靠的做法。
 *
 * 解压目标是容器 rootfs 内的 /opt/rikka-agent, 因此需要 rootfs 已装好。
 */
class AgentInstaller(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val agentRuntime: AgentRuntime,
    private val rootfsInstaller: RootfsInstaller,
) {
    private val _state = MutableStateFlow<AgentInstallState>(AgentInstallState.NotInstalled)
    val state: StateFlow<AgentInstallState> = _state.asStateFlow()

    /** APK 里带的 bundle 版本, 用于判断容器内那份是否过期 */
    private val bundledVersion: String by lazy {
        runCatching {
            context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        }.getOrDefault("unknown")
    }

    /**
     * 检查容器里的 pi 是否已就绪且为当前版本。
     *
     * 比对版本而不是只看文件在不在 —— APK 升级后内置的 pi 也会更新,
     * 容器里那份旧的必须被替换。
     */
    suspend fun check(root: String): Boolean = withContext(Dispatchers.IO) {
        val installedVersion = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = "cat $INSTALL_DIR/VERSION 2>/dev/null || echo __MISSING__",
                cwd = "",
                timeoutMillis = CHECK_TIMEOUT_MS,
            ).stdout.trim()
        }.getOrDefault("__MISSING__")

        val ready = installedVersion.isNotBlank() &&
            installedVersion != "__MISSING__" &&
            installedVersion == bundledVersion

        _state.value = if (ready) {
            AgentInstallState.Ready(installedVersion)
        } else {
            AgentInstallState.NotInstalled
        }
        if (ready) agentRuntime.markInstalled(root) else agentRuntime.markNotInstalled(root)
        ready
    }

    /**
     * 解压内置 bundle 到容器。
     *
     * 走宿主侧的文件操作而不是容器内的 tar —— 最小 rootfs 里未必有 tar,
     * 而且宿主直接写文件比经过 proot 快得多。
     */
    suspend fun install(root: String): Result<String> = withContext(Dispatchers.IO) {
        _state.value = AgentInstallState.Installing("正在解压内置运行时…")

        val assetName = bundleAssetName()
        val linuxDir = workspaceManager.linuxDir(root)
        if (!File(linuxDir, "bin/sh").isFile) {
            val message = "请先安装 Rootfs"
            _state.value = AgentInstallState.Failed(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        val targetDir = File(linuxDir, INSTALL_DIR.trimStart('/'))
        return@withContext runCatching {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            // 先落到 cacheDir 再解压 —— 解压实现要 File(需要随机读取), assets 是流
            val staged = File(context.cacheDir, "agent-bundle.tar.gz")
            context.assets.open(assetName).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            try {
                rootfsInstaller.extractArchive(staged, targetDir)
            } finally {
                staged.delete()
            }

            // 补执行位: tar 里的权限位在部分设备上不被保留, 不加这步 node 跑不起来
            File(targetDir, "runtime/bin/node").setExecutable(true, false)
            File(targetDir, "runtime/pi/pi").setExecutable(true, false)

            File(targetDir, "VERSION").writeText(bundledVersion)

            _state.value = AgentInstallState.Ready(bundledVersion)
            agentRuntime.markInstalled(root)
            bundledVersion
        }.onFailure {
            Log.e(TAG, "Install failed", it)
            _state.value = AgentInstallState.Failed(it.message ?: "解压失败")
        }
    }

    /** 按设备架构选 bundle —— arm64 手机和 x86_64 模拟器的 Node 二进制不通用 */
    private fun bundleAssetName(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val arch = if (abi.startsWith("x86")) "x64" else "arm64"
        return "agent/agent-$arch.tar.gz"
    }

    private companion object {
        private const val VERSION_ASSET = "agent/agent-version.txt"
        private const val INSTALL_DIR = "/opt/rikka-agent"
        private const val CHECK_TIMEOUT_MS = 15_000L
    }
}
