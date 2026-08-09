package me.rerere.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TunnelRunner"

data class TunnelState(
    val isRunning: Boolean = false,
    val hostname: String? = null,
    val error: String? = null,
    /** 连续失败次数, 用于设置页展示"正在重试" */
    val retryCount: Int = 0,
)

/**
 * cloudflared 连接器进程的生命周期管理。
 *
 * 与原 RikkaTunnel 的多隧道版本不同, 这里只跑**一条**隧道 —— 它的唯一用途是把本机
 * 的 chat web 服务暴露到公网, 不是通用的隧道管理器。所以没有 metrics 端口散列、
 * 没有 per-tunnel HOME 隔离那些多开才需要的东西。
 *
 * 进程守护逻辑(指数退避 + 上限)沿用原实现: 网络抖动导致的退出应该快速重连,
 * 但令牌失效那种重试再多也没用的错误不该把电量烧光。
 */
class TunnelRunner(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private var process: Process? = null
    private var supervisorJob: Job? = null

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    /**
     * 启动隧道。
     *
     * @param token Cloudflare 隧道运行令牌
     * @param hostname 公开主机名, 仅用于状态展示 —— 实际路由由云端 ingress 配置决定
     */
    fun start(token: String, hostname: String) {
        if (supervisorJob?.isActive == true) {
            Log.w(TAG, "Tunnel already running")
            return
        }
        if (!CloudflaredManager.isBinaryReady(context)) {
            _state.value = TunnelState(error = "cloudflared binary is not available")
            return
        }
        supervisorJob = scope.launch { supervise(token, hostname) }
    }

    fun stop() {
        supervisorJob?.cancel()
        supervisorJob = null
        killProcess()
        _state.value = TunnelState()
    }

    /**
     * 看门狗循环。
     *
     * 与容器的固定间隔看门狗不同, 这里用指数退避: 隧道断开多半是网络问题,
     * 立刻重连往往能成; 但如果是令牌过期, 密集重试只会耗电, 所以退避到上限。
     */
    private suspend fun supervise(token: String, hostname: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        var retries = 0

        while (scope.isActive) {
            val exitCode = runCatching { runConnector(token, hostname) }
                .onFailure { Log.e(TAG, "Connector failed to start", it) }
                .getOrNull()

            if (!scope.isActive) break

            retries++
            _state.value = TunnelState(
                isRunning = false,
                hostname = hostname,
                error = "Connector exited (code=$exitCode), retrying in ${backoffMs / 1000}s",
                retryCount = retries,
            )
            Log.w(TAG, "cloudflared exited with $exitCode, backing off ${backoffMs}ms")

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** 跑一次连接器, 阻塞到进程退出, 返回退出码 */
    private suspend fun runConnector(token: String, hostname: String): Int {
        val binary = CloudflaredManager.binaryFile(context)
        val home = CloudflaredManager.homeDir(context)
        val log = CloudflaredManager.logFile(context)

        val proc = ProcessBuilder(
            binary.absolutePath,
            "--no-autoupdate",
            "tunnel",
            "run",
            "--token",
            token,
        ).apply {
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = File(context.cacheDir, "tunnel").apply { mkdirs() }.absolutePath
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }.start()

        process = proc
        _state.value = TunnelState(isRunning = true, hostname = hostname)
        Log.i(TAG, "cloudflared started for $hostname")

        return runCatching { proc.waitFor() }.getOrDefault(-1)
    }

    private fun killProcess() {
        runCatching { process?.destroyForcibly() }
        process = null
    }

    private companion object {
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
