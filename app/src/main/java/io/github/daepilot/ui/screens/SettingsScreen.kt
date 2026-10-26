package io.github.daepilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.BuildConfig
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.DiagnosticsDialog
import io.github.daepilot.ui.components.GroupLabel
import io.github.daepilot.ui.components.KeyValueRow
import io.github.daepilot.ui.components.ListGroup
import io.github.daepilot.ui.components.ListRow
import io.github.daepilot.ui.components.ModuleSetupCard
import io.github.daepilot.ui.components.RootSetupCard
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader

private val logLevels = listOf("trace", "debug", "info", "warn", "error", "fatal")
private val themeModes = listOf(
    "system" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    status: DaemonStatus,
    compatibility: CompatibilityReport?,
    onOpenLogs: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showBypassDialog by remember { mutableStateOf(false) }
    var showLevelDialog by remember { mutableStateOf(false) }
    var showUploadConfirm by remember { mutableStateOf(false) }
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val upload by viewModel.logUpload.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = ScreenEdge,
            end = ScreenEdge,
            top = 4.dp,
            bottom = ScreenBottom,
        ),
    ) {
        item {
            ScreenHeader(
                title = "设置",
                subtitle = "DaePilot ${BuildConfig.VERSION_NAME} · 模块 ${BuildConfig.MODULE_VERSION}",
            )
        }

        if (status.mode == "no-root") {
            item {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    RootSetupCard(
                        requesting = rootState.requesting,
                        message = rootState.message,
                        onRequest = viewModel::requestRoot,
                    )
                }
            }
        }
        if (status.mode == "module-missing") {
            item {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    ModuleSetupCard(
                        installing = rootState.installingModule,
                        onInstall = viewModel::installModule,
                    )
                }
            }
        }

        item { GroupLabel("运行") }
        item {
            ListGroup {
                SwitchRow(
                    title = "开机自启",
                    description = "写入模块 boot_enabled，由 service.sh 拉起 dae",
                    checked = snapshot.settings.bootStart,
                    onCheckedChange = viewModel::setBootStart,
                )
                RowDivider()
                SwitchRow(
                    title = "后台常驻通知",
                    description = "低优先级前台服务，仅用于显示状态",
                    checked = snapshot.settings.keepServiceRunning,
                    onCheckedChange = viewModel::setKeepService,
                )
                RowDivider()
                ListRow(
                    title = "TProxy 端口",
                    description = "透明代理监听端口",
                    onClick = { showPortDialog = true },
                    trailing = {
                        Text(
                            snapshot.settings.tproxyPort.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                RowDivider()
                ListRow(
                    title = "日志等级",
                    description = "dae 运行日志的详细程度",
                    onClick = { showLevelDialog = true },
                    trailing = {
                        Text(
                            snapshot.settings.logLevel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        item { GroupLabel("订阅") }
        item {
            ListGroup {
                SwitchRow(
                    title = "自动更新订阅",
                    description = "每 6 小时检查一次；更新后自动热重载",
                    checked = snapshot.settings.subscriptionAutoUpdate,
                    onCheckedChange = viewModel::setGlobalAutoUpdate,
                )
            }
        }

        item { GroupLabel("网络") }
        item {
            ListGroup {
                ListRow(
                    title = "分应用绕过",
                    description = if (snapshot.settings.bypassPackages.isEmpty()) {
                        "未设置，所有应用流量都进入代理"
                    } else {
                        "${snapshot.settings.bypassPackages.size} 个包名直接连接"
                    },
                    onClick = { showBypassDialog = true },
                    trailing = {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        item { GroupLabel("外观") }
        item {
            ListGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    themeModes.forEach { (value, label) ->
                        FilterChip(
                            selected = snapshot.settings.themeMode == value,
                            onClick = {
                                viewModel.updateSettings { it.copy(themeMode = value) }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        item { GroupLabel("维护") }
        item {
            ListGroup {
                ListRow(
                    title = "兼容性检查",
                    leadingIcon = Icons.Outlined.MonitorHeart,
                    onClick = {
                        showDiagnostics = true
                        viewModel.diagnose()
                    },
                    trailing = {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                RowDivider()
                ListRow(
                    title = "运行日志",
                    leadingIcon = Icons.AutoMirrored.Outlined.Article,
                    onClick = onOpenLogs,
                    trailing = {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                RowDivider()
                ListRow(
                    title = "上传运行日志",
                    description = when {
                        upload.uploading -> "正在上传…"
                        upload.lastId != null -> "已上传 · 编号 ${upload.lastId}"
                        upload.error != null -> upload.error
                        else -> "生成诊断信息并发送到项目服务器"
                    },
                    leadingIcon = Icons.Outlined.CloudUpload,
                    onClick = if (upload.uploading) null else {
                        { showUploadConfirm = true }
                    },
                    trailing = {
                        if (upload.uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                if (status.mode == "module-missing") {
                    RowDivider()
                    ListRow(
                        title = "安装内置模块",
                        leadingIcon = Icons.Outlined.CloudDownload,
                        onClick = if (rootState.installingModule) null else {
                            { viewModel.installModule() }
                        },
                        trailing = {
                            if (rootState.installingModule) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    "安装",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
                RowDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyValueRow("运行模式", modeLabel(status.mode))
                    status.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item { GroupLabel("关于") }
        item {
            ListGroup {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KeyValueRow("DaePilot", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow("dae", status.version ?: BuildConfig.DAE_VERSION)
                    KeyValueRow("模块", BuildConfig.MODULE_VERSION)
                    KeyValueRow("数据面路径", "/data/adb/daepilot")
                }
                RowDivider()
                Text(
                    "数据面使用 dae（AGPL-3.0）。应用不内置任何订阅或节点，" +
                        "订阅与节点只保存在本机，不会自动上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            report = compatibility,
            loading = false,
            onRun = viewModel::diagnose,
            onDismiss = { showDiagnostics = false },
        )
    }

    if (showPortDialog) {
        PortDialog(
            current = snapshot.settings.tproxyPort,
            onDismiss = { showPortDialog = false },
            onSave = {
                viewModel.setTproxyPort(it)
                showPortDialog = false
            },
        )
    }

    if (showLevelDialog) {
        LogLevelDialog(
            current = snapshot.settings.logLevel,
            onDismiss = { showLevelDialog = false },
            onSave = {
                viewModel.setLogLevel(it)
                showLevelDialog = false
            },
        )
    }

    if (showBypassDialog) {
        BypassDialog(
            current = snapshot.settings.bypassPackages,
            onDismiss = { showBypassDialog = false },
            onSave = {
                viewModel.setBypassPackages(it)
                showBypassDialog = false
            },
        )
    }

    if (showUploadConfirm) {
        ConfirmDialog(
            title = "上传运行日志",
            message = "将发送当前运行状态、设备信息、最近操作记录、脱敏后的配置与最近日志到项目服务器。" +
                "配置中的账号、密码、UUID、token 会在本机隐藏；日志仍可能包含节点地址与访问域名。",
            confirmLabel = "上传",
            onConfirm = { viewModel.uploadLogs() },
            onDismiss = { showUploadConfirm = false },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PortDialog(
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(current.toString()) }
    val port = value.toIntOrNull()
    val valid = port != null && port in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TProxy 端口") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all(Char::isDigit)) value = input
                },
                label = { Text("1 - 65535") },
                singleLine = true,
                isError = value.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(onClick = { port?.let(onSave) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LogLevelDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日志等级") },
        text = {
            Column {
                logLevels.forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = level }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == level, onClick = { selected = level })
                        Text(level, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BypassDialog(
    current: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(current.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分应用绕过") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "填写进程名（pname），每行一个，也可用逗号分隔。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("com.example.app\ncom.tencent.mm") },
                    minLines = 4,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.small,
                )
                Text(
                    "Android 进程名最长 15 个字符，超长包名可能无法精确匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val packages = text
                        .split(Regex("[\\s,;]+"))
                        .map { it.trim() }
                        .filter { it.matches(Regex("[A-Za-z0-9_.:]+")) }
                        .distinct()
                    onSave(packages)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun modeLabel(mode: String): String = when (mode) {
    "no-root" -> "未授权 root"
    "module-missing" -> "模块未安装"
    "chroot" -> "chroot"
    "unknown" -> "未知"
    else -> mode
}
