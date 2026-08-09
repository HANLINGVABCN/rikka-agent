package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.workspace.ProotProcessLauncher

private const val TAG = "AgentRuntime"

/**
 * Agent 模式的运行时。
 *
 * 打开 agent 模式后, chat 的对话**整个交给容器里的 pi** —— 用户消息直接送进
 * pi 的 RPC 会话, pi 的流式输出就是助手回复。主模型完全不参与, 因此也不存在
 * "主模型调用 agent 工具"这回事。
 *
 * 会话是长驻的: 同一个对话里的多轮消息进同一个 pi 进程, 上下文由 pi 自己维护。
 */
class AgentRuntime(
    private val launcher: ProotProcessLauncher,
    private val settingsStore: SettingsStore,
) {
    private var session: PiRpcSession? = null
    private var sessionRoot: String? = null

    private val _state = MutableStateFlow<AgentRuntimeState>(AgentRuntimeState.Stopped)
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    val isRunning: Boolean get() = session?.isAlive == true

    /**
     * 确保 pi 会话就绪。
     *
     * provider 与 model 取自当前设置里选中的那个 —— agent 用跟 chat 同一个模型,
     * 免得用户要配两次。API key 从 [ProviderSetting] 里取。
     */
    suspend fun ensureSession(workspaceId: String): Result<PiRpcSession> {
        val existing = session
        if (existing != null && existing.isAlive && sessionRoot == workspaceId) {
            return Result.success(existing)
        }
        stop()

        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel()
            ?: return Result.failure(IllegalStateException("No chat model selected"))
        val provider = model.findProvider(settings.providers)
            ?: return Result.failure(IllegalStateException("Model has no provider"))

        val piProvider = provider.toPiProviderName()
            ?: return Result.failure(
                IllegalStateException("Provider ${provider.name} is not supported by pi")
            )
        val apiKey = provider.apiKeyOrNull().orEmpty()
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Provider ${provider.name} has no API key"))
        }

        _state.value = AgentRuntimeState.Starting
        return runCatching {
            PiRpcSession(launcher = launcher, root = workspaceId).also {
                it.start(provider = piProvider, model = model.modelId, apiKey = apiKey)
                session = it
                sessionRoot = workspaceId
                _state.value = AgentRuntimeState.Running(workspaceId)
            }
        }.onFailure {
            Log.e(TAG, "Failed to start pi session", it)
            _state.value = AgentRuntimeState.Failed(it.message ?: "start failed")
        }
    }

    fun stop() {
        session?.stop()
        session = null
        sessionRoot = null
        _state.value = AgentRuntimeState.Stopped
    }

    fun interrupt() {
        session?.interrupt()
    }

    private companion object {
        /** pi 支持的 provider 名, 与 ProviderSetting 的三种类型对应 */
        fun ProviderSetting.toPiProviderName(): String? = when (this) {
            is ProviderSetting.Claude -> "anthropic"
            is ProviderSetting.OpenAI -> "openai"
            is ProviderSetting.Google -> "google"
            else -> null
        }

        fun ProviderSetting.apiKeyOrNull(): String? = when (this) {
            is ProviderSetting.Claude -> apiKey
            is ProviderSetting.OpenAI -> apiKey
            is ProviderSetting.Google -> apiKey
            else -> null
        }
    }
}

sealed interface AgentRuntimeState {
    data object Stopped : AgentRuntimeState
    data object Starting : AgentRuntimeState
    data class Running(val workspaceId: String) : AgentRuntimeState
    data class Failed(val message: String) : AgentRuntimeState
}
