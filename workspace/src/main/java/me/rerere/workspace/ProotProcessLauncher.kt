package me.rerere.workspace

import java.io.File

/**
 * 在容器里启动一个长连接进程, 由调用方自己读写它的 stdin/stdout。
 *
 * 与 [WorkspaceShellRunner] 的区别: 那个是"发命令 → 等结束 → 拿结果", 这个是把
 * [Process] 直接交出去, 用于 pi 的 RPC 模式那种需要双向流式通信的场景。
 *
 * 调用方负责关闭返回的进程。
 */
class ProotProcessLauncher(
    private val nativeLibraryDir: File,
    private val baseDir: File,
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    class LaunchException(message: String) : Exception(message)

    /**
     * 在容器内启动 [command]。
     *
     * stderr **不**合并进 stdout —— RPC 协议要求 stdout 是干净的 JSONL,
     * 混入日志会让解析失败。
     */
    fun launch(root: String, command: List<String>, env: Map<String, String> = emptyMap()): Process {
        val workspaceDir = File(baseDir, root)
        val linuxDir = File(workspaceDir, LINUX_DIR)
        val filesDir = File(workspaceDir, FILES_DIR)
        val tempDir = File(workspaceDir, TEMP_DIR)

        if (!linuxDir.isDirectory || !File(linuxDir, "bin/sh").isFile) {
            throw LaunchException("Rootfs is not installed for workspace: $root")
        }
        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) throw LaunchException("proot executable not found")
        if (!loader.isFile) throw LaunchException("proot loader not found")

        tempDir.mkdirs()
        patcher.patch(linuxDir)

        val argv = buildArgv(proot, linuxDir, filesDir, command, env)
        return ProcessBuilder(argv)
            .directory(filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()
    }

    private fun buildArgv(
        proot: File,
        linuxDir: File,
        filesDir: File,
        command: List<String>,
        env: Map<String, String>,
    ): List<String> {
        val argv = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            // 不加 --kill-on-exit: 这里跑的是常驻服务, 不是一次性命令
            "-r",
            linuxDir.absolutePath,
            "-w",
            WorkspaceManager.ROOTFS_WORKSPACE_DIR,
            "-b",
            "${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}",
        )
        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                argv += "-b"
                argv += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }
        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                argv += "-b"
                argv += path
            }
        }
        argv += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
        // API key 之类通过 env 传, 不进命令行 —— /proc/*/cmdline 对同 UID 进程可读
        env.forEach { (key, value) -> argv += "$key=$value" }
        argv += command
        return argv
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
    }
}
