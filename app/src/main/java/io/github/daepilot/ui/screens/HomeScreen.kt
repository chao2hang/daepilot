package io.github.daepilot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.BuildConfig
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.model.TrafficInfo
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.components.DiagnosticsDialog
import io.github.daepilot.ui.components.KeyValueRow
import io.github.daepilot.ui.components.LatencyBadge
import io.github.daepilot.ui.components.ListGroup
import io.github.daepilot.ui.components.ListRow
import io.github.daepilot.ui.components.ModuleSetupCard
import io.github.daepilot.ui.components.RootSetupCard
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.components.SectionCard
import io.github.daepilot.ui.components.StatusBadge
import io.github.daepilot.ui.components.TrafficBar

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    status: DaemonStatus,
    busy: Boolean,
    compatibility: CompatibilityReport?,
    onOpenNodes: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val selectedNode = snapshot.nodes.firstOrNull { it.id == snapshot.selectedNodeId }
    val aggregateTraffic = remember(snapshot.subscriptions) {
        val list = snapshot.subscriptions.mapNotNull { it.traffic }
        if (list.isEmpty()) {
            null
        } else {
            TrafficInfo(
                uploadBytes = list.sumOf { it.uploadBytes },
                downloadBytes = list.sumOf { it.downloadBytes },
                totalBytes = list.sumOf { it.totalBytes },
                expireAtEpochSeconds = list.map { it.expireAtEpochSeconds }
                    .filter { it > 0 }.minOrNull() ?: 0,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = ScreenEdge,
            end = ScreenEdge,
            top = 4.dp,
            bottom = ScreenBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "DaePilot",
                subtitle = "dae ${status.version ?: BuildConfig.DAE_VERSION} · ${modeLabel(status.mode)}",
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        }

        item {
            StatusPanel(
                status = status,
                busy = busy,
                bootStart = snapshot.settings.bootStart,
                onToggle = { viewModel.toggleProxy(!status.running) },
            )
        }

        if (status.mode == "no-root") {
            item {
                RootSetupCard(
                    requesting = rootState.requesting,
                    message = rootState.message,
                    onRequest = viewModel::requestRoot,
                )
            }
        }
        if (status.mode == "module-missing") {
            item {
                ModuleSetupCard(
                    installing = rootState.installingModule,
                    onInstall = viewModel::installModule,
                )
            }
        }

        item {
            ListGroup {
                ListRow(
                    title = selectedNode?.name ?: "自动选择节点",
                    description = selectedNode?.let {
                        "${it.protocol.label} · ${it.endpoint?.host ?: "—"}"
                    } ?: "按移动平均延迟自动切换",
                    leadingIcon = Icons.Outlined.Dns,
                    onClick = onOpenNodes,
                    trailing = {
                        if (selectedNode != null) {
                            LatencyBadge(
                                selectedNode.latencyMs,
                                tag = if (selectedNode.protocol.isUdpOnly) "ICMP" else null,
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
                RowDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KeyValueRow(
                        "运行时长",
                        if (status.running && status.startedAt > 0) {
                            Format.duration((System.currentTimeMillis() - status.startedAt) / 1000)
                        } else {
                            "—"
                        },
                    )
                    KeyValueRow(
                        "内存占用",
                        if (status.running) Format.memory(status.rssKb) else "—",
                    )
                    KeyValueRow(
                        "开机自启",
                        when {
                            status.bootEnabled -> "已开启"
                            snapshot.settings.bootStart -> "已开启，尚未写入模块"
                            else -> "已关闭"
                        },
                    )
                    KeyValueRow(
                        "watchdog",
                        if (status.watchdog) "运行中" else "未运行",
                        valueColor = if (status.watchdog) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        item {
            if (aggregateTraffic != null) {
                SectionCard(
                    title = "订阅流量",
                    subtitle = "来自订阅服务器返回的 userinfo",
                    action = { TextButton(onClick = onOpenSubscriptions) { Text("管理") } },
                ) {
                    TrafficBar(aggregateTraffic)
                }
            } else {
                SectionCard(
                    title = "订阅",
                    subtitle = "导入节点后才能启动代理",
                    action = { TextButton(onClick = onOpenSubscriptions) { Text("添加") } },
                ) {
                    Text(
                        "支持分享链接、base64 列表、Clash YAML 与 sing-box JSON。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::testAllNodes,
                    enabled = snapshot.nodes.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("测延迟") }
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("日志") }
                OutlinedButton(
                    onClick = {
                        showDiagnostics = true
                        viewModel.diagnose()
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("检查") }
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            report = compatibility,
            loading = busy,
            onRun = viewModel::diagnose,
            onDismiss = { showDiagnostics = false },
        )
    }
}

@Composable
private fun StatusPanel(
    status: DaemonStatus,
    busy: Boolean,
    bootStart: Boolean,
    onToggle: () -> Unit,
) {
    val running = status.running
    val title = when {
        running -> "代理运行中"
        status.mode == "no-root" -> "未授权 root"
        status.mode == "module-missing" -> "模块未安装"
        else -> "代理未运行"
    }
    val detail = when {
        running && status.pid != null -> "PID ${status.pid} · 由模块 watchdog 托管"
        running -> "由模块 watchdog 托管"
        status.mode == "no-root" -> "启动时需要允许 root 授权"
        status.mode == "module-missing" -> "root 已就绪，安装模块后即可启动"
        bootStart -> "开机时会自动启动"
        else -> "点击下方按钮启动透明代理"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                StatusBadge(if (running) "运行中" else "已停止", running)
            }

            val buttonModifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
            if (running) {
                OutlinedButton(
                    onClick = onToggle,
                    enabled = !busy,
                    modifier = buttonModifier,
                    shape = MaterialTheme.shapes.small,
                ) {
                    ActionLabel(
                        loading = busy,
                        icon = { Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)) },
                        text = "停止代理",
                    )
                }
            } else {
                Button(
                    onClick = onToggle,
                    enabled = !busy,
                    modifier = buttonModifier,
                    shape = MaterialTheme.shapes.small,
                ) {
                    ActionLabel(
                        loading = busy,
                        icon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)) },
                        text = "启动代理",
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(
    loading: Boolean,
    icon: @Composable () -> Unit,
    text: String,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = LocalContentColor.current,
        )
        Spacer(Modifier.size(8.dp))
        Text("处理中…")
    } else {
        icon()
        Spacer(Modifier.size(8.dp))
        Text(text)
    }
}

private fun modeLabel(mode: String): String = when (mode) {
    "no-root" -> "未授权 root"
    "module-missing" -> "模块未安装"
    "chroot" -> "chroot 模式"
    "unknown" -> "正在读取状态"
    else -> mode
}
