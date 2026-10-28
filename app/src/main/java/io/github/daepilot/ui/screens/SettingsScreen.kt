package io.github.daepilot.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
import io.github.daepilot.ui.theme.AccentChoice

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
    var showAccentSheet by remember { mutableStateOf(false) }
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
                subtitle = "${BuildConfig.VERSION_NAME.substringBefore("-debug")} · 模块 ${BuildConfig.MODULE_VERSION}",
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
                    checked = snapshot.settings.bootStart,
                    onCheckedChange = viewModel::setBootStart,
                )
                RowDivider()
                SwitchRow(
                    title = "后台常驻通知",
                    checked = snapshot.settings.keepServiceRunning,
                    onCheckedChange = viewModel::setKeepService,
                )
                RowDivider()
                ListRow(
                    title = "TProxy 端口",
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
                        "未设置"
                    } else {
                        "${snapshot.settings.bypassPackages.size} 个包名"
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
                ThemeModeControl(
                    selected = snapshot.settings.themeMode,
                    onSelect = { value ->
                        viewModel.updateSettings { it.copy(themeMode = value) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                RowDivider()
                AccentRow(
                    current = effectiveAccent(snapshot.settings.accentColor),
                    onClick = { showAccentSheet = true },
                )
            }
        }

        item { GroupLabel("维护") }
        item {
            ListGroup {
                ListRow(
                    title = "运行诊断",
                    description = "网络 · DNS · 内核",
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
                        else -> "生成诊断信息"
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
                    KeyValueRow("DaePilot", "${BuildConfig.VERSION_NAME.substringBefore("-debug")} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow("dae", status.version ?: BuildConfig.DAE_VERSION)
                    KeyValueRow("模块", BuildConfig.MODULE_VERSION)
                    KeyValueRow("数据面路径", "/data/adb/daepilot")
                }
                RowDivider()
                Text(
                    "DAE · AGPL-3.0 · 仅本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            report = compatibility,
            status = status,
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
            pnameUnavailable = compatibility?.pnameRouting == false,
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

    if (showAccentSheet) {
        AccentColorSheet(
            current = effectiveAccent(snapshot.settings.accentColor),
            onDismiss = { showAccentSheet = false },
            onSelect = { choice ->
                viewModel.updateSettings { it.copy(accentColor = choice.id) }
            },
        )
    }
}

private fun effectiveAccent(id: String): AccentChoice {
    val choice = AccentChoice.fromId(id)
    return if (choice.isSystem && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        AccentChoice.FOREST
    } else {
        choice
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String? = null,
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
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    pnameUnavailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(current.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分应用绕过") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pnameUnavailable) {
                    Text(
                        "本机 dae 未能挂载 cgroup 钩子，pname 规则不会生效——保存后这些进程仍会走代理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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

@Composable
private fun ThemeModeControl(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                0.75.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                RoundedCornerShape(13.dp),
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        themeModes.forEach { (value, label) ->
            val active = value == selected
            val background by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.surfaceBright
                } else {
                    Color.Transparent
                },
                animationSpec = tween(180),
                label = "themeModeBackground",
            )
            val foreground by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180),
                label = "themeModeForeground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = foreground)
            }
        }
    }
}

@Composable
private fun AccentRow(
    current: AccentChoice,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("主题色", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            current.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        AccentDot(choice = current, size = 18.dp)
        Spacer(Modifier.width(5.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccentDot(
    choice: AccentChoice,
    size: androidx.compose.ui.unit.Dp,
) {
    val brush = if (choice.isSystem) {
        systemColorBrush()
    } else {
        SolidColor(choice.swatch)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(brush)
            .border(0.75.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape),
    )
}

private fun systemColorBrush(): Brush = Brush.sweepGradient(
    listOf(
        Color(0xFF8C5A5A),
        Color(0xFF9C8354),
        Color(0xFF5F7F65),
        Color(0xFF566F8A),
        Color(0xFF726288),
        Color(0xFF8C5A5A),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentColorSheet(
    current: AccentChoice,
    onDismiss: () -> Unit,
    onSelect: (AccentChoice) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text("主题色", style = MaterialTheme.typography.headlineSmall)
            Text(
                "按钮、开关与选中项会使用这个颜色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            ) {
                AccentChoice.entries.filterNot { it.isSystem }.forEach { choice ->
                    AccentOption(
                        choice = choice,
                        selected = current == choice,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(choice)
                        },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 24.dp),
                    thickness = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                )
                SystemColorRow(
                    selected = current.isSystem,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(AccentChoice.SYSTEM)
                    },
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp),
            ) {
                Text("完成", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AccentOption(
    choice: AccentChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            CircleShape,
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SolidColor(choice.swatch))
                    .border(
                        0.75.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        CircleShape,
                    ),
            )
        }
        Text(
            choice.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SystemColorRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(systemColorBrush())
                .border(
                    0.75.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("跟随壁纸", style = MaterialTheme.typography.bodyLarge)
            Text(
                "从系统壁纸取色，只用于强调色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "no-root" -> "未授权 Root"
    "module-missing" -> "模块未安装"
    "chroot" -> "chroot"
    "unknown" -> "未知"
    else -> mode
}
