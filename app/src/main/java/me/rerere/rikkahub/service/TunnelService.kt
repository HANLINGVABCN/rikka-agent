package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.TUNNEL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.tunnel.CloudflareApi
import me.rerere.tunnel.TunnelRunner
import org.koin.android.ext.android.inject

private const val TAG = "TunnelService"

/**
 * Cloudflare Tunnel 前台服务。
 *
 * 把本机的 chat web 服务经 cloudflared 暴露到公网。隧道本身不提供内容 —— 它只是
 * 一根管子, 另一头必须是已经在跑的 [WebServerService], 否则公网访问会得到 502。
 */
class TunnelService : Service() {

    private val tunnelRunner: TunnelRunner by inject()
    private val cloudflareApi: CloudflareApi by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tunnelRunner.stop()
                serviceScope.launch {
                    settingsStore.update { it.copy(tunnelEnabled = false) }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObservingState()
                serviceScope.launch { startTunnel() }
            }
        }
        // 隧道是用户显式开启的长期服务, 被系统杀掉后应该自己回来
        return START_STICKY
    }

    override fun onDestroy() {
        tunnelRunner.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 取运行令牌并拉起连接器。
     *
     * 令牌不落盘 —— 它等价于隧道的完全控制权, 每次现取。API 令牌本身已经存在设置里,
     * 再存一份运行令牌只是多一个泄露面。
     */
    private suspend fun startTunnel() {
        val settings = settingsStore.settingsFlowRaw.first()
        val apiToken = settings.tunnelApiToken
        val tunnelId = settings.tunnelId
        val hostname = settings.tunnelHostname

        if (apiToken.isBlank() || tunnelId.isBlank() || hostname.isBlank()) {
            Log.w(TAG, "Tunnel is not configured")
            updateNotification(getString(R.string.notification_tunnel_error, "not configured"))
            stopSelf()
            return
        }

        val runToken = runCatching { cloudflareApi.getRunToken(apiToken, tunnelId) }
            .getOrElse {
                Log.e(TAG, "Failed to fetch run token", it)
                updateNotification(
                    getString(R.string.notification_tunnel_error, it.message ?: "token error")
                )
                return
            }

        tunnelRunner.start(token = runToken, hostname = hostname)
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            tunnelRunner.state.collect { state ->
                val text = when {
                    state.error != null -> getString(R.string.notification_tunnel_error, state.error)
                    state.isRunning -> state.hostname.orEmpty()
                    else -> getString(R.string.notification_tunnel_starting)
                }
                updateNotification(text)
            }
        }
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_tunnel_starting)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_tunnel_starting))
                )
            }
            true
        } catch (e: Exception) {
            // 与 WebServerService 同样的 OEM 兼容问题: 部分 ROM 在系统侧拒绝 FGS 类型
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_tunnel_running))
            .setContentText(text)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_tunnel_stop), stopIntent)
            .build()
    }

    companion object {
        // 2001=WebServer, 2002=Container, 2003=Tunnel —— 同 ID 会互相顶掉常驻通知
        private const val NOTIFICATION_ID = 2003

        const val ACTION_START = "me.rerere.rikkahub.action.TUNNEL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.TUNNEL_STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TunnelService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TunnelService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
