package me.rerere.workspace

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 常驻容器会话。
 *
 * [ProotShellRunner] 每条命令起一个 proot 进程, 命令之间不共享任何状态:
 * cd 不保留、export 不保留、后台进程随进程组一起死。本类改成维持**一个**长活的
 * proot bash, 命令通过 stdin 送进去, 靠哨兵标记切分输出, 从而做到:
 *
 * - `cd /tmp` 之后下一条命令仍在 /tmp
 * - `export FOO=1` 之后下一条命令读得到
 * - `nohup pi-agent &` 起的后台进程在命令返回后继续活着
 *
 * 生命周期由宿主的前台服务持有(见 app 模块 ContainerService), 只要开关是开的,
 * 这个 bash 就一直在。[isAlive] 为 false 时调用方应回落到一次性 runner。
 */
class PersistentShellSession(
    private val root: String,
    private val nativeLibraryDir: File,
    private val filesDir: File,
    private val linuxDir: File,
    private val tempDir: File,
    private val bindMounts: List<WorkspaceBindMount>,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderr: BufferedReader? = null

    // proot bash 是单条管道, 一次只能跑一条命令; 并发调用必须排队
    private val lock = ReentrantLock()

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** 启动常驻 bash。已在运行时是空操作。 */
    fun start() = lock.withLock {
        if (isAlive) return@withLock
        check(linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile) {
            "Rootfs is not installed for workspace: $root"
        }
        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        check(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        check(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        tempDir.mkdirs()
        patcher.patch(linuxDir)

        val proc = ProcessBuilder(buildCommand(proot))
            .directory(filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()

        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        stdout = proc.inputStream.bufferedReader()
        stderr = proc.errorStream.bufferedReader()
    }

    /**
     * 在常驻会话里执行一条命令。
     *
     * 实现要点: 每条命令后面追加一行 `echo <哨兵> $?`, 读到哨兵即知命令结束并拿到退出码。
     * stderr 同样打哨兵, 否则无法判断该读到哪里为止。
     */
    fun execute(command: String, cwd: String, timeoutMillis: Long): WorkspaceCommandResult =
        lock.withLock {
            if (!isAlive) {
                return@withLock WorkspaceCommandResult(
                    exitCode = 127,
                    stdout = "",
                    stderr = "Persistent container session is not running",
                )
            }
            val marker = "__RIKKA_${UUID.randomUUID().toString().replace("-", "")}__"
            val w = writer ?: return@withLock notRunning()
            val targetCwd = prootCwd(cwd)

            try {
                // 命令本身放进函数体执行, 避免 set -e 之类的设置影响哨兵输出
                w.write("cd -- ${shellQuote(targetCwd)} 2>/dev/null; ")
                w.write(command)
                w.write("\n")
                // $? 必须紧跟命令, 中间不能插别的
                w.write("__rc=\$?; echo \"$marker \$__rc\"; echo \"$marker\" >&2\n")
                w.flush()
            } catch (e: IOException) {
                closeQuietly()
                return@withLock WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Container session died: ${e.message}",
                )
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val out = readUntilMarker(stdout, marker, deadline)
            val err = readUntilMarker(stderr, marker, deadline)

            if (out.timedOut || err.timedOut) {
                // 超时的命令还占着管道, 会话已不可信 —— 杀掉重启, 否则后续命令读到的是上一条的残留输出
                restartAfterTimeout()
                return@withLock WorkspaceCommandResult(
                    exitCode = -1,
                    stdout = out.text,
                    stderr = err.text,
                    timedOut = true,
                    truncated = out.truncated || err.truncated,
                )
            }

            WorkspaceCommandResult(
                exitCode = out.exitCode ?: 0,
                stdout = out.text,
                stderr = err.text,
                truncated = out.truncated || err.truncated,
            )
        }

    fun stop() = lock.withLock { closeQuietly() }

    private fun notRunning() = WorkspaceCommandResult(
        exitCode = 127,
        stdout = "",
        stderr = "Persistent container session is not running",
    )

    private fun restartAfterTimeout() {
        closeQuietly()
        runCatching { start() }
    }

    private fun closeQuietly() {
        runCatching { writer?.close() }
        runCatching { stdout?.close() }
        runCatching { stderr?.close() }
        runCatching { process?.destroyForcibly() }
        writer = null
        stdout = null
        stderr = null
        process = null
    }

    private data class ReadResult(
        val text: String,
        val exitCode: Int?,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    /**
     * 读到哨兵行为止。
     *
     * 这里必须用 [BufferedReader.ready] 轮询而不是直接阻塞 readLine: 命令超时时
     * 哨兵永远不会来, 阻塞读会把调用线程永久挂住。
     */
    private fun readUntilMarker(
        reader: BufferedReader?,
        marker: String,
        deadlineNanos: Long,
    ): ReadResult {
        if (reader == null) return ReadResult("", null, timedOut = true, truncated = false)
        val builder = StringBuilder()
        var truncated = false
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                return ReadResult(builder.toString(), null, timedOut = true, truncated = truncated)
            }
            if (!reader.ready()) {
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            val line = reader.readLine()
                ?: return ReadResult(builder.toString(), null, timedOut = true, truncated = truncated)
            if (line.startsWith(marker)) {
                val code = line.removePrefix(marker).trim().toIntOrNull()
                return ReadResult(builder.toString(), code, timedOut = false, truncated = truncated)
            }
            // 与一次性 runner 保持同样的输出上限, 防止刷屏命令撑爆 LLM 上下文
            if (builder.length < MAX_OUTPUT_CHARS) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(line)
            } else {
                truncated = true
            }
        }
    }

    private fun buildCommand(proot: File): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            // 注意: 不能加 --kill-on-exit, 那会让后台进程随命令一起死, 与常驻语义相悖
            "-r",
            linuxDir.absolutePath,
            "-w",
            WorkspaceManager.ROOTFS_WORKSPACE_DIR,
            "-b",
            "${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}",
        )
        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }
        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            // 交互式 shell 会打印提示符污染输出, 这里只要一个读 stdin 的非交互 shell
            "-s",
        )
        return command
    }

    private fun prootCwd(cwd: String): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val POLL_INTERVAL_MS = 10L
    }
}
