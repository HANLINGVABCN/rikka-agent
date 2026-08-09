package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.PersistentProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    // 常驻容器 runner: 由 ContainerService 控制会话生命周期, 会话未启动时回落到一次性 proot 进程。
    // 单独 single 出来是因为前台服务和设置页都要拿到同一个实例来开关容器。
    single<PersistentProotShellRunner> {
        val context: Context = get()
        PersistentProotShellRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            baseDir = File(context.filesDir, "workspaces"),
            bindMounts = workspaceBindMounts(context),
        )
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = get<PersistentProotShellRunner>(),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = workspaceBindMounts(context),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}

/**
 * 工作区绑定挂载表。
 *
 * 一次性 runner、常驻 runner 和 [WorkspaceManager] 的路径解析都读这一份,
 * 三处必须一致, 否则同一个 rootfs 路径在不同 runner 下会落到不同宿主目录。
 */
internal fun workspaceBindMounts(context: Context): List<WorkspaceBindMount> = listOf(
    WorkspaceBindMount(
        source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
        target = "/skills",
    ),
    WorkspaceBindMount(
        source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
        target = "/tool_outputs",
    ),
    WorkspaceBindMount(
        source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
        target = "/upload",
    ),
)
