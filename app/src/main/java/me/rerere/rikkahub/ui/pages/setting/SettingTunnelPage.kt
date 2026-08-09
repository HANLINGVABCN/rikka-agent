package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.TunnelService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tunnel.CloudflareApi
import me.rerere.tunnel.CloudflaredManager
import me.rerere.tunnel.TunnelRunner
import org.koin.compose.koinInject

/**
 * 隧道设置页。
 *
 * 隧道只做一件事: 把 [SettingWebPage] 那个 web 服务暴露到公网。因此这里没有
 * 多隧道管理 —— 一个域名、一条隧道、一个开关。
 */
@Composable
fun SettingTunnelPage() {
    val settingsStore: SettingsStore = koinInject()
    val tunnelRunner: TunnelRunner = koinInject()
    val cloudflareApi: CloudflareApi = koinInject()
    val webServerManager: WebServerManager = koinInject()
    val settings = LocalSettings.current
    val tunnelState by tunnelRunner.state.collectAsStateWithLifecycle()
    val webState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current

    var apiTokenText by remember(settings.tunnelApiToken) {
        mutableStateOf(settings.tunnelApiToken)
    }
    var hostnameText by remember(settings.tunnelHostname) {
        mutableStateOf(settings.tunnelHostname)
    }
    var tokenVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val binaryReady = remember { CloudflaredManager.isBinaryReady(context) }
    val authConfigured = settings.webServerAccessPassword.isNotBlank()
    val configured = settings.tunnelId.isNotBlank() && settings.tunnelHostname.isNotBlank()

    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    val authRequiredMessage = stringResource(R.string.setting_page_tunnel_auth_required)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tunnel)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (busy) return@ExtendedFloatingActionButton
                    if (tunnelState.isRunning) {
                        TunnelService.stop(context)
                        scope.launch { settingsStore.update { it.copy(tunnelEnabled = false) } }
                        return@ExtendedFloatingActionButton
                    }
                    // 隧道开启会强制 JWT(见 Settings.effectiveJwtEnabled), 而密码为空时
                    // 所有路由 fail-closed —— 不拦住的话用户会把自己锁在外面
                    if (!authConfigured) {
                        toaster.show(authRequiredMessage)
                        return@ExtendedFloatingActionButton
                    }
                    scope.launch {
                        settingsStore.update { it.copy(tunnelEnabled = true) }
                        TunnelService.start(context)
                    }
                },
                icon = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (tunnelState.isRunning) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (tunnelState.isRunning) {
                            stringResource(R.string.setting_page_tunnel_stop)
                        } else {
                            stringResource(R.string.setting_page_tunnel_start)
                        }
                    )
                },
                containerColor = if (tunnelState.isRunning) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_tunnel_status)) },
                        supportingContent = {
                            Text(
                                tunnelState.error
                                    ?: tunnelState.hostname?.takeIf { tunnelState.isRunning }
                                    ?: stringResource(R.string.setting_page_tunnel_status_stopped)
                            )
                        },
                        trailingContent = {
                            Text(
                                if (tunnelState.isRunning) {
                                    stringResource(R.string.setting_page_tunnel_status_running)
                                } else {
                                    stringResource(R.string.setting_page_tunnel_status_stopped)
                                },
                                color = if (tunnelState.isRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    )
                    if (!binaryReady) {
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.setting_page_tunnel_binary_missing),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                    if (!webState.isRunning) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_tunnel_web_required))
                            },
                        )
                    }
                    if (!authConfigured) {
                        item(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.setting_page_tunnel_auth_required),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    } else {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_tunnel_auth_forced))
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_tunnel_api_token)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tunnel_api_token_desc)) },
                        trailingContent = {
                            TextField(
                                value = apiTokenText,
                                onValueChange = { value ->
                                    apiTokenText = value
                                    scope.launch {
                                        settingsStore.update { it.copy(tunnelApiToken = value) }
                                    }
                                },
                                visualTransformation = if (tokenVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                        Icon(
                                            imageVector = if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                singleLine = true,
                                enabled = !tunnelState.isRunning,
                                modifier = Modifier.width(180.dp),
                                shape = CircleShape,
                                colors = transparentFieldColors(),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_tunnel_hostname)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tunnel_hostname_desc)) },
                        trailingContent = {
                            TextField(
                                value = hostnameText,
                                onValueChange = { value ->
                                    hostnameText = value.trim()
                                    scope.launch {
                                        settingsStore.update { it.copy(tunnelHostname = hostnameText) }
                                    }
                                },
                                singleLine = true,
                                enabled = !tunnelState.isRunning,
                                modifier = Modifier.width(180.dp),
                                shape = CircleShape,
                                colors = transparentFieldColors(),
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = {
                            Text(
                                if (configured) {
                                    stringResource(R.string.setting_page_tunnel_setup_done)
                                } else {
                                    stringResource(R.string.setting_page_tunnel_setup)
                                }
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.setting_page_tunnel_setup_desc)) },
                        trailingContent = {
                            TextButton(
                                enabled = !busy && !tunnelState.isRunning &&
                                    apiTokenText.isNotBlank() && hostnameText.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                setupTunnel(
                                                    api = cloudflareApi,
                                                    apiToken = apiTokenText,
                                                    hostname = hostnameText,
                                                    port = settings.webServerPort,
                                                    existingTunnelId = settings.tunnelId,
                                                )
                                            }
                                        }.onSuccess { tunnelId ->
                                            settingsStore.update { it.copy(tunnelId = tunnelId) }
                                            toaster.show("OK")
                                        }.onFailure {
                                            toaster.show(it.message ?: "failed")
                                        }
                                        busy = false
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.setting_page_tunnel_setup))
                            }
                        },
                    )
                    if (configured) {
                        item(
                            headlineContent = { Text("https://${settings.tunnelHostname}") },
                            onClick = {
                                clipboard.setText(AnnotatedString("https://${settings.tunnelHostname}"))
                                toaster.show(context.getString(R.string.copied))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 建隧道 + 写路由。
 *
 * 已有隧道 id 时复用, 否则新建 —— 每次点都新建会在 Cloudflare 账号里堆一堆废隧道。
 */
private suspend fun setupTunnel(
    api: CloudflareApi,
    apiToken: String,
    hostname: String,
    port: Int,
    existingTunnelId: String,
): String {
    val tunnelId = existingTunnelId.ifBlank {
        api.createTunnel(apiToken, "rikka-agent-${hostname.replace('.', '-')}").id
    }
    api.route(apiToken, tunnelId, hostname, port)
    return tunnelId
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
