package io.github.daepilot.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.BuildConfig
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.TrafficInfo
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.components.DiagnosticsDialog
import io.github.daepilot.ui.components.LatencyBadge
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge

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
            top = 0.dp,
            bottom = ScreenBottom,
        ),
    ) {
        item {
            BrandRow(
                busy = busy,
                daeVersion = status.version ?: BuildConfig.DAE_VERSION,
            )
        }
        item {
            Hero(
                status = status,
                selectedNode = selectedNode,
                busy = busy,
                setupLabel = when (status.mode) {
                    "no-root" -> "授权"
                    "module-missing" -> "安装"
                    else -> null
                },
                setupLoading = (status.mode == "no-root" && rootState.requesting) ||
                    (status.mode == "module-missing" && rootState.installingModule),
                onSetup = when (status.mode) {
                    "no-root" -> ({ viewModel.requestRoot() })
                    "module-missing" -> ({ viewModel.installModule() })
                    else -> null
                },
                onToggle = { viewModel.toggleProxy(!status.running) },
            )
        }
        item {
            SignalStrip(status = status, bootRequested = snapshot.settings.bootStart)
        }
        item {
            ExitSection(selectedNode = selectedNode, onOpenNodes = onOpenNodes)
        }
        item {
            MetricsRow(status = status)
        }
        item {
            ProfilesSection(
                traffic = aggregateTraffic,
                onOpenSubscriptions = onOpenSubscriptions,
            )
        }
        item {
            ActionStrip(
                canTest = snapshot.nodes.isNotEmpty() && !busy,
                onTest = viewModel::testAllNodes,
                onLogs = onOpenLogs,
                onDiagnose = {
                    showDiagnostics = true
                    viewModel.diagnose()
                },
            )
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
private fun BrandRow(busy: Boolean, daeVersion: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "DAEPILOT",
            style = MaterialTheme.typography.labelLarge.copy(
                letterSpacing = 2.8.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "v${BuildConfig.VERSION_NAME.substringBefore("-debug")} · dae $daeVersion",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) {
            Spacer(Modifier.width(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Hero(
    status: DaemonStatus,
    selectedNode: Node?,
    busy: Boolean,
    setupLabel: String?,
    setupLoading: Boolean,
    onSetup: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val running = status.running
    val setup = status.mode == "no-root" || status.mode == "module-missing"
    val title = when {
        running -> "运行中"
        status.mode == "no-root" -> "需要 Root"
        status.mode == "module-missing" -> "需要模块"
        else -> "已停止"
    }
    val detail = when {
        running -> selectedNode?.name ?: "透明代理已接管"
        status.mode == "no-root" -> "在 Magisk / KernelSU 中授权"
        status.mode == "module-missing" -> "安装 dae 数据面模块"
        else -> selectedNode?.name ?: "选择节点后启动"
    }
    val eyebrow = when {
        running -> "ONLINE"
        setup -> "SETUP"
        else -> "STANDBY"
    }
    val tone = when {
        running -> MaterialTheme.colorScheme.primary
        setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 30.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                )
            }
            Spacer(Modifier.height(13.dp))
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (setupLabel != null && onSetup != null) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = onSetup,
                        enabled = !setupLoading,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        ),
                    ) {
                        if (setupLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        } else {
                            Text(setupLabel, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        PowerButton(running = running, setup = setup, busy = busy, onToggle = onToggle)
    }
}

@Composable
private fun PowerButton(
    running: Boolean,
    setup: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val fill = if (running) MaterialTheme.colorScheme.primary else Color.Transparent
    val border = when {
        running -> MaterialTheme.colorScheme.primary
        setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val content = when {
        running -> MaterialTheme.colorScheme.onPrimary
        setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onBackground
    }
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = !busy, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 1.5.dp,
                color = content,
            )
        } else {
            Icon(
                Icons.Outlined.PowerSettingsNew,
                contentDescription = if (running) "停止代理" else "启动代理",
                tint = content,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun SignalStrip(status: DaemonStatus, bootRequested: Boolean) {
    val rootReady = status.mode != "no-root"
    val bootValue = when {
        status.bootEnabled -> "ON"
        bootRequested -> "PENDING"
        else -> "OFF"
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalCell(
            label = "ROOT",
            value = if (rootReady) "OK" else "—",
            active = rootReady,
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        SignalCell(
            label = "WATCHDOG",
            value = if (status.watchdog) "ON" else "OFF",
            active = status.watchdog,
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        SignalCell(
            label = "BOOT",
            value = bootValue,
            active = status.bootEnabled,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SignalCell(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ExitSection(selectedNode: Node?, onOpenNodes: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "EXIT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenNodes, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("更换", style = MaterialTheme.typography.labelLarge)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                selectedNode?.name ?: "自动选择",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                selectedNode?.let {
                    "${it.protocol.label} · ${it.endpoint?.host ?: "—"}"
                } ?: "AUTO",
                style = if (selectedNode == null) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (selectedNode != null) {
            Spacer(Modifier.width(12.dp))
            LatencyBadge(
                selectedNode.latencyMs,
                tag = if (selectedNode.protocol.isUdpOnly) "ICMP" else null,
            )
        }
    }
}

@Composable
private fun MetricsRow(status: DaemonStatus) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Metric(
            label = "UPTIME",
            value = if (status.running && status.startedAt > 0) {
                Format.duration((System.currentTimeMillis() - status.startedAt) / 1000)
            } else {
                "—"
            },
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        Metric(
            label = "MEMORY",
            value = if (status.running) Format.memory(status.rssKb) else "—",
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        Metric(
            label = "DAE",
            value = status.version ?: BuildConfig.DAE_VERSION,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfilesSection(traffic: TrafficInfo?, onOpenSubscriptions: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "PROFILES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenSubscriptions, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (traffic == null) "导入" else "管理", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (traffic == null) {
        Column(modifier = Modifier.padding(bottom = 22.dp)) {
            Text(
                "未导入订阅",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "CLASH · SING-BOX · SHARE LINK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    } else {
        Column(modifier = Modifier.padding(bottom = 22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    Format.bytes(traffic.usedBytes),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (traffic.totalBytes > 0) Format.bytes(traffic.totalBytes) else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(traffic.usageFraction.coerceIn(0f, 1f))
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            if (traffic.expireAtEpochSeconds > 0) {
                Text(
                    "到期 ${Format.expiry(traffic.expireAtEpochSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionStrip(
    canTest: Boolean,
    onTest: () -> Unit,
    onLogs: () -> Unit,
    onDiagnose: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onTest, enabled = canTest) {
            Text("测延迟", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onLogs) {
            Text("日志", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onDiagnose) {
            Text("诊断", style = MaterialTheme.typography.labelLarge)
        }
    }
}
