package me.rerere.rikkahub.data.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager

private const val TAG = "AgentDeployer"

/** pi agent 在容器里的部署状态 */
sealed interface AgentDeployState {
    data object NotDeployed : AgentDeployState
    data class Deploying(val log: String) : AgentDeployState
    data class Ready(val version: String) : AgentDeployState
    data class Failed(val message: String) : AgentDeployState
}

/**
 * 把 pi agent 部署进容器。
 *
 * 部署逻辑本身是 assets 里的 shell 脚本 —— 装 Node、装 npm 包这些事用 shell 写
 * 天然合适, 用 Kotlin 拼字符串只会更脆。这个类只负责把脚本送进去、跑起来、
 * 把结果读回来。
 *
 * 依赖容器已装好 rootfs; 部署过程要下载 ~50MB(Node + pi 及其依赖), 需要联网。
 */
class AgentDeployer(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val agentRuntime: AgentRuntime,
) {
    private val _state = MutableStateFlow<AgentDeployState>(AgentDeployState.NotDeployed)
    val state: StateFlow<AgentDeployState> = _state.asStateFlow()

    /**
     * 检查容器里的 pi 是否可用。
     *
     * 用 `command -v` 而不是 `pi --version` —— 后者在 pi 装了但依赖坏掉时会挂住,
     * 前者只查 PATH, 立刻返回。
     */
    suspend fun check(root: String): Boolean = withContext(Dispatchers.IO) {
        val result = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = "command -v pi >/dev/null 2>&1 && pi --version 2>/dev/null || echo __MISSING__",
                cwd = "",
                timeoutMillis = CHECK_TIMEOUT_MS,
            )
        }.getOrNull() ?: return@withContext false

        val output = result.stdout.trim()
        val ready = result.exitCode == 0 && output.isNotBlank() && !output.contains("__MISSING__")
        _state.value = if (ready) AgentDeployState.Ready(output) else AgentDeployState.NotDeployed
        // 同步给 runtime —— workspace_agent 工具靠这个标记决定要不要暴露
        if (ready) agentRuntime.markDeployed(root) else agentRuntime.markNotDeployed(root)
        ready
    }

    /**
     * 执行部署。耗时较长(装 Node + npm 包), 调用方应该在后台跑并展示进度。
     */
    suspend fun deploy(root: String): Result<String> = withContext(Dispatchers.IO) {
        _state.value = AgentDeployState.Deploying("")

        val script = runCatching {
            context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            val message = "Cannot read deploy script: ${it.message}"
            _state.value = AgentDeployState.Failed(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        // 脚本先落到容器内再执行, 而不是 `bash -c "<整个脚本>"` —— 后者会让脚本内容
        // 经过一次 shell 引号解析, 里面的 $ 和引号很容易被吃掉
        val writeResult = runCatching {
            workspaceManager.writeText(
                root = root,
                path = SCRIPT_PATH,
                text = script,
                overwrite = true,
            )
        }
        if (writeResult.isFailure) {
            val message = "Cannot write deploy script: ${writeResult.exceptionOrNull()?.message}"
            _state.value = AgentDeployState.Failed(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        val result = runCatching {
            workspaceManager.executeCommand(
                root = root,
                command = "bash /workspace/$SCRIPT_PATH 2>&1",
                cwd = "",
                timeoutMillis = DEPLOY_TIMEOUT_MS,
            )
        }.getOrElse {
            val message = it.message ?: "deploy failed"
            Log.e(TAG, "Deploy failed", it)
            _state.value = AgentDeployState.Failed(message)
            return@withContext Result.failure(it)
        }

        val log = (result.stdout + "\n" + result.stderr).trim()
        return@withContext if (result.exitCode == 0) {
            val version = runCatching {
                workspaceManager.executeCommand(
                    root = root,
                    command = "pi --version 2>/dev/null || echo unknown",
                    cwd = "",
                    timeoutMillis = CHECK_TIMEOUT_MS,
                ).stdout.trim()
            }.getOrDefault("unknown")
            _state.value = AgentDeployState.Ready(version)
            agentRuntime.markDeployed(root)
            Result.success(log)
        } else {
            _state.value = AgentDeployState.Failed(log.takeLast(MAX_ERROR_CHARS))
            Result.failure(IllegalStateException("Deploy exited with ${result.exitCode}"))
        }
    }

    private companion object {
        private const val SCRIPT_ASSET = "container/deploy-pi.sh"
        private const val SCRIPT_PATH = ".rikka/deploy-pi.sh"
        private const val CHECK_TIMEOUT_MS = 15_000L

        // 装 Node + npm install 在慢网下很久, 给足时间总比中途被砍好
        private const val DEPLOY_TIMEOUT_MS = 600_000L
        private const val MAX_ERROR_CHARS = 2000
    }
}
