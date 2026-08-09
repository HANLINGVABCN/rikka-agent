package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CONTAINER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.workspace.PersistentProotShellRunner
import org.koin.android.ext.android.inject

private const val TAG = "ContainerService"

/**
 * 常驻容器前台服务。
 *
 * 只要开关是开的, 这个服务就把对应 workspace 的 proot bash 一直守在那里:
 * 用户切后台、清最近任务、容器进程被 OOM killer 干掉, 都会被看门狗拉回来。
 *
 * 与 [WebServerService] 的区别是这里用 START_STICKY —— 容器的语义就是"一直存活",
 * 被系统杀掉后必须自己回来。
 */
class ContainerService : Service() {
    private val shellRunner: PersistentProotShellRunner by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null
    private var activeRoot: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopContainer()
                return START_NOT_STICKY
            }

            else -> {
                // START_STICKY 重建时 intent 为空, 用上次的 root 恢复
                val root = intent?.getStringExtra(EXTRA_ROOT)
                    ?: activeRoot
                    ?: loadPersistedRoot()
                if (root == null) {
                    Log.w(TAG, "No workspace root to start, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat(root)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startContainer(root)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        watchdogJob = null
        releaseWakeLock()
        // 服务停止即容器停止 —— 不留孤儿 proot 进程
        shellRunner.stopAll()
        activeRoot = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startContainer(root: String) {
        if (activeRoot == root && shellRunner.isSessionAlive(root)) return
        activeRoot = root
        persistRoot(root)
        acquireWakeLock()

        runCatching { shellRunner.startSession(root) }
            .onFailure {
                Log.e(TAG, "Failed to start container session for $root", it)
                updateNotification(buildNotification(root, running = false, error = it.message))
            }
            .onSuccess {
                updateNotification(buildNotification(root, running = true))
            }

        startWatchdog(root)
    }

    private fun stopContainer() {
        watchdogJob?.cancel()
        watchdogJob = null
        activeRoot?.let { shellRunner.stopSession(it) }
        activeRoot = null
        clearPersistedRoot()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 看门狗: 容器被系统或 OOM killer 杀掉后拉回来。
     *
     * 用固定间隔而非指数退避 —— 容器不像网络连接, 起不来通常是 rootfs 坏了这种
     * 重试再多也没用的问题, 退避只会让正常的意外死亡恢复得更慢。
     */
    private fun startWatchdog(root: String) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (activeRoot != root) break
                if (!shellRunner.isSessionAlive(root)) {
                    Log.w(TAG, "Container session died, restarting: $root")
                    val restarted = runCatching { shellRunner.restartIfDead(root) }
                        .onFailure { Log.e(TAG, "Watchdog restart failed", it) }
                        .getOrDefault(false)
                    updateNotification(
                        buildNotification(
                            root = root,
                            running = shellRunner.isSessionAlive(root),
                            error = if (!restarted) "restart failed" else null,
                        )
                    )
                }
            }
        }
    }

    private fun startForegroundCompat(root: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(root, running = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(root, running = false))
            }
            true
        } catch (e: Exception) {
            // 与 WebServerService 同样的 OEM 兼容问题: 部分 ROM 会在系统侧拒绝 FGS 类型
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RikkaHub::container").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Failed to acquire wakelock", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun updateNotification(notification: android.app.Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        root: String,
        running: Boolean,
        error: String? = null,
    ): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ContainerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            error != null -> getString(R.string.notification_container_error, error)
            running -> root
            else -> getString(R.string.notification_container_starting)
        }
        return NotificationCompat.Builder(this, CONTAINER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_container_running))
            .setContentText(text)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_container_stop), stopIntent)
            .build()
    }

    private fun persistRoot(root: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_ROOT, root).apply()
    }

    private fun loadPersistedRoot(): String? =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_ROOT, null)

    private fun clearPersistedRoot() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACTIVE_ROOT).apply()
    }

    companion object {
        // 不能用 2001 —— WebServerService 占了, 同 ID 会互相顶掉对方的常驻通知
        private const val NOTIFICATION_ID = 2002
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val PREFS = "container_service"
        private const val KEY_ACTIVE_ROOT = "active_root"

        const val ACTION_START = "me.rerere.rikkahub.action.CONTAINER_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.CONTAINER_STOP"
        const val EXTRA_ROOT = "root"

        fun start(context: Context, root: String) {
            val intent = Intent(context, ContainerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROOT, root)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContainerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
