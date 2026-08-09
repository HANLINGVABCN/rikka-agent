package me.rerere.rikkahub.data.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.workspace.ProotProcessLauncher
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PiRpcSession"

/**
 * 与容器内 `pi --mode rpc` 的一条会话。
 *
 * 协议是 LF 分隔的 JSONL over stdin/stdout: 命令写进 stdin, 事件从 stdout 流出。
 * pi 的文档明确要求**只按 `\n` 切分** —— 不能用会把 U+2028/U+2029 也当换行的
 * 通用行读取器, 那两个字符在 JSON 字符串里是合法的。这里用手写的字节级切分。
 */
class PiRpcSession(
    private val launcher: ProotProcessLauncher,
    private val root: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private val requestId = AtomicLong(0)

    private val _events = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        // 事件比 UI 消费快时丢最老的 —— 阻塞 reader 会把 pi 的 stdout 堵死
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val isAlive: Boolean get() = process?.isAlive == true

    /**
     * 启动 pi RPC 进程。
     *
     * @param apiKey 传给 pi 的 API key, 走环境变量而不是命令行参数 ——
     *   /proc/<pid>/cmdline 对同 UID 的进程可读, 命令行会泄露密钥。
     */
    fun start(provider: String, model: String, apiKey: String) {
        if (isAlive) return

        val env = buildMap {
            put(providerKeyEnv(provider), apiKey)
        }
        val proc = launcher.launch(
            root = root,
            command = listOf(
                "pi",
                "--mode", "rpc",
                "--provider", provider,
                "--model", model,
            ),
            env = env,
        )
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        _running.value = true

        readerJob = newScope.launch { readEvents(proc) }
        // stderr 单独排空: 不读的话管道缓冲区满了 pi 就会卡住
        newScope.launch { drainStderr(proc) }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        scope?.cancel()
        scope = null
        writer = null
        process = null
        _running.value = false
    }

    /**
     * 发一条 prompt 给 agent。事件从 [events] 异步流出, 这里只负责投递。
     *
     * @param streamingBehavior agent 正在生成时必须指定, 否则 pi 会拒绝该命令。
     *   "steer" 插队到当前回合结束后, "followUp" 等 agent 完全停下。
     */
    fun prompt(message: String, streamingBehavior: String? = null): String? {
        val id = "req-${requestId.incrementAndGet()}"
        val command = buildJsonObject {
            put("id", id)
            put("type", "prompt")
            put("message", message)
            if (streamingBehavior != null) put("streamingBehavior", streamingBehavior)
        }
        return if (send(command)) id else null
    }

    fun interrupt(): Boolean = send(buildJsonObject { put("type", "interrupt") })

    private fun send(command: JsonObject): Boolean {
        val w = writer ?: return false
        return runCatching {
            // 协议要求一条命令一行, 且分隔符只能是 LF
            w.write(command.toString())
            w.write("\n")
            w.flush()
            true
        }.getOrElse {
            Log.e(TAG, "Failed to send command", it)
            stop()
            false
        }
    }

    /**
     * 按 LF 切分读取事件。
     *
     * 手写切分而不是 BufferedReader.readLine(): 后者把 \r 也当行尾, 而 JSON 字符串里
     * 的 \r 是合法内容; pi 文档也明确要求只按 \n 切。
     */
    private suspend fun readEvents(proc: Process) {
        val input = proc.inputStream
        val buffer = StringBuilder()
        val chunk = ByteArray(READ_CHUNK)

        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.append(String(chunk, 0, read, Charsets.UTF_8))

                var newlineIndex = buffer.indexOf("\n")
                while (newlineIndex >= 0) {
                    val line = buffer.substring(0, newlineIndex)
                    buffer.delete(0, newlineIndex + 1)
                    // 协议允许输入侧带 \r\n, 收到时剥掉尾部的 \r
                    emitLine(line.removeSuffix("\r"))
                    newlineIndex = buffer.indexOf("\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Event reader stopped", e)
        } finally {
            _running.value = false
        }
    }

    private suspend fun emitLine(line: String) {
        if (line.isBlank()) return
        val event = runCatching { json.parseToJsonElement(line).jsonObject }
            .getOrElse {
                Log.w(TAG, "Unparseable RPC line: ${line.take(200)}")
                return
            }
        _events.emit(event)
    }

    private fun drainStderr(proc: Process) {
        runCatching {
            proc.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) Log.d(TAG, "pi stderr: $line")
            }
        }
    }

    private companion object {
        private const val EVENT_BUFFER = 256
        private const val READ_CHUNK = 8192

        /** pi 按提供商读不同的环境变量名 */
        fun providerKeyEnv(provider: String): String = when (provider.lowercase()) {
            "anthropic" -> "ANTHROPIC_API_KEY"
            "openai" -> "OPENAI_API_KEY"
            "google" -> "GEMINI_API_KEY"
            else -> "${provider.uppercase()}_API_KEY"
        }
    }
}

/** 从 pi 的事件里抽出可显示的文本增量 */
fun JsonObject.piTextDelta(): String? {
    if (this["type"]?.jsonPrimitive?.contentOrNull != "message_update") return null
    val event = this["assistantMessageEvent"]?.jsonObject ?: return null
    if (event["type"]?.jsonPrimitive?.contentOrNull != "text_delta") return null
    return event["delta"]?.jsonPrimitive?.contentOrNull
}

/** agent 是否已完全停下(不会再自动重试或继续队列) */
fun JsonObject.piSettled(): Boolean =
    this["type"]?.jsonPrimitive?.contentOrNull == "agent_settled"
