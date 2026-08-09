package me.rerere.tunnel

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 内置 cloudflared 连接器的路径与运行目录。
 *
 * 二进制由 CI 在构建时按 CPU 架构打包进 jniLibs, 安装时系统把匹配架构的那份解到
 * 只读的 `nativeLibraryDir`。Android 10+ 禁止执行可写数据目录里的二进制, 所以内核
 * 必须随包内置, 不能运行时下载到 filesDir 再执行。
 *
 * 与 `:workspace` 的 proot 是同一个手法 —— 那边是 libproot_exec.so, 这边是 libcloudflared.so。
 */
object CloudflaredManager {
    private const val BINARY_NAME = "libcloudflared.so"

    /** 安装时系统解压出的可执行文件 */
    fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * 二进制是否可用。
     *
     * 检查大小是因为构建脚本下载失败时可能留下一个几百字节的错误页面, 那种文件
     * 存在且"可执行", 但一跑就废。
     */
    fun isBinaryReady(context: Context): Boolean {
        val file = binaryFile(context)
        return file.exists() && file.canExecute() && file.length() > MIN_BINARY_SIZE
    }

    /** cloudflared 会在 HOME 下写 `.cloudflared/` 状态 */
    fun homeDir(context: Context): File =
        File(context.filesDir, "tunnel/home").apply { mkdirs() }

    fun logFile(context: Context): File =
        File(context.filesDir, "tunnel/cloudflared.log").apply { parentFile?.mkdirs() }

    /** 当前设备 CPU 架构, 仅用于设置页展示 */
    fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private const val MIN_BINARY_SIZE = 1_000_000L
}
