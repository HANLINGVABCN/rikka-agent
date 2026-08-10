package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.workspace.ProotProcessLauncher
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AgentRuntime"

// agent 可能跑很久(装包、编译), 但不能无限等 —— 超时只停止等待, pi 进程仍在容器里
private const val TASK_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * 容器内 pi agent 的运行时。
 *
 * pi 以**工具**的形式提供给模型(`workspace_agent`), 而不是接管对话 —— 对话历史、
 * 分支、重新生成全部仍由 app 这边管理, 与上游的 workspace 工具是同一套模型。
 *
 * pi 用的是当前 chat 选中的那个模型和 API key, 用户不需要另外配置。
 */
class AgentRuntime(
    private val launcher: ProotProcessLauncher,
    private val settingsStore: SettingsStore,
) {
    /** 已确认装好 pi 的 workspace —— 避免每次建工具都去容器里查一次 */
    private val installed = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isInstalled(workspaceId: String): Boolean = installed.contains(workspaceId)

    /** 由安装流程在解压完成后调用 */
    fun markInstalled(workspaceId: String) {
        installed.add(workspaceId)
    }

    fun markNotInstalled(workspaceId: String) {
        installed.remove(workspaceId)
    }

    /**
     * 交给 pi 一个任务, 等它自己迭代到做完, 返回最终报告。
     *
     * 每个任务一个独立的 pi 进程: 任务之间不该共享上下文 —— 模型每次调用工具时给的是
     * 一个自包含的目标, 上一个任务的历史留着只会污染判断, 也会让 token 越滚越多。
     */
    suspend fun runTask(workspaceId: String, task: String): Result<String> {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel()
            ?: return Result.failure(IllegalStateException("No chat model selected"))
        val provider = model.findProvider(settings.providers)
            ?: return Result.failure(IllegalStateException("Model has no provider"))
        val piProvider = provider.toPiProviderName()
            ?: return Result.failure(
                IllegalStateException("Provider ${provider.name} is not supported by pi (needs Anthropic/OpenAI/Google)")
            )
        val apiKey = provider.apiKeyOrNull().orEmpty()
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Provider ${provider.name} has no API key"))
        }

        val session = PiRpcSession(launcher = launcher, root = workspaceId)
        return try {
            session.start(provider = piProvider, model = model.modelId, apiKey = apiKey)

            val transcript = StringBuilder()
            val settled = CompletableDeferred<Unit>()
            // 结束判定放在同一个 collector 里: events 是 replay=0 的 SharedFlow,
            // 事后再 first{} 的话, pi 回得快时结束事件会在订阅建立前就发完, 一路等到超时
            val collector = scope.launch {
                session.events.collect { event ->
                    event.piTextDelta()?.let { transcript.append(it) }
                    if (event.piSettled()) settled.complete(Unit)
                }
            }

            try {
                // launch 只是排队, 协程可能还没开始 collect —— 等订阅真正建立再发
                session.awaitSubscriber()

                if (session.prompt(task) == null) {
                    return Result.failure(IllegalStateException("pi rejected the task"))
                }
                val finished = withTimeoutOrNull(TASK_TIMEOUT_MS) { settled.await() } != null
                val report = transcript.toString().trim()
                Result.success(
                    when {
                        report.isNotBlank() && finished -> report
                        report.isNotBlank() -> "$report\n\n[timed out after 30 minutes]"
                        else -> "Agent finished without producing any output."
                    }
                )
            } finally {
                collector.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent task failed", e)
            Result.failure(e)
        } finally {
            session.stop()
        }
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
