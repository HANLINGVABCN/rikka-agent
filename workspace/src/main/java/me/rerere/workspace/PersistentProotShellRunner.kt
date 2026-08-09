package me.rerere.workspace

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 常驻容器 runner。
 *
 * 每个 workspace root 维持一个 [PersistentShellSession]。会话由外部(前台服务)
 * 通过 [startSession] / [stopSession] 控制生命周期 —— 这正是"打开开关就一直存活"的开关。
 *
 * 会话未启动时**回落到** [fallback](一次性 proot 进程), 保证:
 * - 容器开关关着时, 所有既有功能照常工作(只是没有跨命令状态)
 * - rootfs 没装、proot 起不来等异常不会让工具直接不可用
 */
class PersistentProotShellRunner(
    private val nativeLibraryDir: File,
    private val baseDir: File,
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    private val fallback: WorkspaceShellRunner = ProotShellRunner(nativeLibraryDir),
) : WorkspaceShellRunner {

    private val sessions = ConcurrentHashMap<String, PersistentShellSession>()

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val session = sessions[context.root]
        if (session == null || !session.isAlive) {
            return fallback.execute(context)
        }
        return session.execute(
            command = context.command,
            cwd = context.cwd,
            timeoutMillis = context.timeoutMillis,
        )
    }

    /**
     * 打开容器开关: 启动并常驻。幂等。
     *
     * 目录按 [WorkspaceManager] 的同一套规则拼装 —— 这里无法拿到 [WorkspaceShellContext]
     * (那是每条命令才有的), 所以 [baseDir] 必须与 `WorkspaceManager` 的构造参数是同一个值,
     * 两处已在 DI 里由同一表达式提供。
     */
    fun startSession(root: String) {
        sessions.compute(root) { _, existing ->
            if (existing != null && existing.isAlive) return@compute existing
            newSession(root).apply { start() }
        }
    }

    /** 关闭容器开关: 杀掉常驻会话, 后续命令回落到一次性模式。 */
    fun stopSession(root: String) {
        sessions.remove(root)?.stop()
    }

    fun stopAll() {
        sessions.keys.toList().forEach { stopSession(it) }
    }

    fun isSessionAlive(root: String): Boolean = sessions[root]?.isAlive == true

    /** 供看门狗调用: 会话意外死亡时拉起来。返回是否执行了重启。 */
    fun restartIfDead(root: String): Boolean {
        val session = sessions[root] ?: return false
        if (session.isAlive) return false
        startSession(root)
        return true
    }

    private fun newSession(root: String) = PersistentShellSession(
        root = root,
        nativeLibraryDir = nativeLibraryDir,
        filesDir = File(File(baseDir, root), FILES_DIR),
        linuxDir = File(File(baseDir, root), LINUX_DIR),
        tempDir = File(File(baseDir, root), TEMP_DIR),
        bindMounts = bindMounts,
    )

    private companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
    }
}
