package io.github.daepilot.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.daepilot.ui.components.GlassAction
import io.github.daepilot.ui.components.GlassIconButton
import io.github.daepilot.ui.components.GlassSurface
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.WingMark
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

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
    val setup = status.mode == "no-root" || status.mode == "module-missing"
    val setupLoading = (status.mode == "no-root" && rootState.requesting) ||
        (status.mode == "module-missing" && rootState.installingModule)
    val setupLabel = when (status.mode) {
        "no-root" -> "授权"
        "module-missing" -> "安装模块"
        else -> null
    }
    val onSetup: (() -> Unit)? = when (status.mode) {
        "no-root" -> ({ viewModel.requestRoot() })
        "module-missing" -> ({ viewModel.installModule() })
        else -> null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenEdge,
            end = ScreenEdge,
            top = 6.dp,
            bottom = ScreenBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            HomeHeader(
                busy = busy,
                daeVersion = status.version ?: BuildConfig.DAE_VERSION,
                onOpenSettings = onOpenSettings,
            )
        }
        item {
            HeroCard(
                status = status,
                selectedNode = selectedNode,
                busy = busy,
                setup = setup,
                setupLabel = setupLabel,
                setupLoading = setupLoading,
                onSetup = onSetup,
                onToggle = { viewModel.toggleProxy(!status.running) },
            )
        }
        item {
            SignalCard(status = status, bootRequested = snapshot.settings.bootStart)
        }
        item {
            NodeCard(selectedNode = selectedNode, onOpenNodes = onOpenNodes)
        }
        item {
            TrafficCard(traffic = aggregateTraffic, onOpenSubscriptions = onOpenSubscriptions)
        }
        item {
            QuickActions(
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
            status = status,
            loading = busy,
            onRun = viewModel::diagnose,
            onDismiss = { showDiagnostics = false },
        )
    }
}

@Composable
private fun HomeHeader(
    busy: Boolean,
    daeVersion: String,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tileShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(tileShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1D2B23), Color(0xFF101612)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), tileShape),
            contentAlignment = Alignment.Center,
        ) {
            WingMark(modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("DaePilot", style = MaterialTheme.typography.titleLarge)
            Text(
                "v${BuildConfig.VERSION_NAME.substringBefore("-debug")} · dae $daeVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        GlassIconButton(
            onClick = onOpenSettings,
            icon = Icons.Outlined.Settings,
            contentDescription = "设置",
        )
    }
}

@Composable
private fun HeroCard(
    status: DaemonStatus,
    selectedNode: Node?,
    busy: Boolean,
    setup: Boolean,
    setupLabel: String?,
    setupLoading: Boolean,
    onSetup: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val running = status.running
    val title = when {
        running -> "运行中"
        status.mode == "no-root" -> "需要 Root"
        status.mode == "module-missing" -> "需要模块"
        else -> "已停止"
    }
    val detail = when {
        running -> selectedNode?.let {
            "${it.protocol.label} · ${it.endpoint?.host ?: "—"}"
        } ?: "透明代理已接管全部流量"
        status.mode == "no-root" -> "在 Magisk / KernelSU 中授权后继续"
        status.mode == "module-missing" -> "安装 dae 数据面模块后继续"
        else -> "选择节点，点按电源开始接管"
    }
    val eyebrow = when {
        running -> "已连接"
        setup -> "待设置"
        else -> "待机"
    }
    val tone = when {
        running -> MaterialTheme.colorScheme.primary
        setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    GlassSurface(
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(20.dp),
        spacing = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = tone,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (running) "TProxy" else "LOCAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (setupLabel != null && onSetup != null) {
                    Spacer(Modifier.height(11.dp))
                    SetupPill(
                        label = setupLabel,
                        loading = setupLoading,
                        onClick = onSetup,
                    )
                } else if (running && selectedNode?.latencyMs != null) {
                    Spacer(Modifier.height(11.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "${selectedNode.latencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            PowerOrb(
                running = running,
                setup = setup,
                busy = busy,
                onToggle = onToggle,
            )
        }
    }
}

@Composable
private fun SetupPill(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !loading,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.tertiary,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PowerOrb(
    running: Boolean,
    setup: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalDaeGlassPalette.current
    val transition = rememberInfiniteTransition(label = "powerBreath")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "powerBreathValue",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 780f),
        label = "powerScale",
    )
    val glowAlpha = if (running) 0.24f + 0.16f * breath else 0.06f
    val fill = if (running) {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
            ),
        )
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            ),
        )
    }
    val contentColor = when {
        running -> MaterialTheme.colorScheme.onPrimary
        setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val edgeColor = when {
        running -> Color.Transparent
        setup -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.50f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    Brush.radialGradient(
                        listOf(palette.glow.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = palette.glow,
                    spotColor = palette.glow,
                )
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, edgeColor, CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !busy,
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (pressed) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = 0.12f)),
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (running) "停止代理" else "启动代理",
                    tint = contentColor,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun SignalCard(status: DaemonStatus, bootRequested: Boolean) {
    val rootReady = status.mode != "no-root"
    val bootValue = when {
        status.bootEnabled -> "已开启"
        bootRequested -> "待生效"
        else -> "关闭"
    }
    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 13.dp),
        spacing = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalCell(
                label = "ROOT",
                value = if (rootReady) "正常" else "未授权",
                active = rootReady,
                modifier = Modifier.weight(1f),
            )
            VerticalRule()
            SignalCell(
                label = "后台守护",
                value = if (status.watchdog) "运行中" else "关闭",
                active = status.watchdog,
                modifier = Modifier.weight(1f),
            )
            VerticalRule()
            SignalCell(
                label = "开机自启",
                value = bootValue,
                active = status.bootEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SignalCell(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 13.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    )
}

@Composable
private fun NodeCard(selectedNode: Node?, onOpenNodes: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp),
        spacing = 0.dp,
        onClick = onOpenNodes,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "当前节点",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selectedNode?.name ?: "自动选择",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selectedNode?.let {
                        "${it.protocol.label} · ${it.endpoint?.host ?: "—"}"
                    } ?: "按最低延迟自动切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectedNode?.latencyMs != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "${selectedNode.latencyMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TrafficCard(traffic: TrafficInfo?, onOpenSubscriptions: () -> Unit) {
    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(18.dp),
        spacing = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DataUsage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "订阅流量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (traffic == null) {
                        "未导入订阅"
                    } else {
                        val total = if (traffic.totalBytes > 0) {
                            Format.bytes(traffic.totalBytes)
                        } else {
                            "—"
                        }
                        "${Format.bytes(traffic.usedBytes)} / $total"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (traffic == null) {
                TextButton(
                    onClick = onOpenSubscriptions,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("去导入", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (traffic != null) {
            val tone = when {
                traffic.usageFraction > 0.9f -> MaterialTheme.colorScheme.error
                traffic.usageFraction > 0.7f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(traffic.usageFraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
            }
            if (traffic.expireAtEpochSeconds > 0) {
                Text(
                    "到期 ${Format.expiry(traffic.expireAtEpochSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "导入订阅后可在这里查看用量和到期时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickActions(
    canTest: Boolean,
    onTest: () -> Unit,
    onLogs: () -> Unit,
    onDiagnose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassAction(
            icon = Icons.Outlined.Speed,
            label = "测延迟",
            onClick = onTest,
            enabled = canTest,
            modifier = Modifier.weight(1f),
        )
        GlassAction(
            icon = Icons.AutoMirrored.Outlined.Article,
            label = "日志",
            onClick = onLogs,
            modifier = Modifier.weight(1f),
        )
        GlassAction(
            icon = Icons.Outlined.MonitorHeart,
            label = "诊断",
            onClick = onDiagnose,
            modifier = Modifier.weight(1f),
        )
    }
}
