package me.rerere.rikkahub.di

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import java.io.File
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.data.agent.AgentDeployer
import me.rerere.rikkahub.data.agent.AgentRuntime
import me.rerere.workspace.ProotProcessLauncher
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tunnel.CloudflareApi
import me.rerere.tunnel.TunnelRunner
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            agentRuntime = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }

    // 隧道: TunnelService 与设置页共用同一个 runner 实例, 否则设置页读不到运行状态
    single<TunnelRunner> { TunnelRunner(context = get()) }
    single<CloudflareApi> { CloudflareApi() }

    single<AgentDeployer> { AgentDeployer(context = get(), workspaceManager = get(), agentRuntime = get()) }

    // Agent 模式: 容器内常驻 pi RPC 会话。ProotProcessLauncher 与 WorkspaceManager
    // 共用同一个 baseDir/挂载表, 否则 pi 看到的文件系统与工具看到的不是同一份。
    single<ProotProcessLauncher> {
        val ctx: Context = get()
        ProotProcessLauncher(
            nativeLibraryDir = File(ctx.applicationInfo.nativeLibraryDir),
            baseDir = File(ctx.filesDir, "workspaces"),
            bindMounts = workspaceBindMounts(ctx),
        )
    }
    single<AgentRuntime> { AgentRuntime(launcher = get(), settingsStore = get()) }
}
