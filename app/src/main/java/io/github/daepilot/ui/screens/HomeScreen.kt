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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.BuildConfig
import io.github.daepilot.core.log.LogAlerts
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.network.DomainTarget
import io.github.daepilot.core.network.DomainTargets
import io.github.daepilot.core.network.DomainTestResult
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.BandwidthState
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.SiteProbeState
import io.github.daepilot.ui.components.ConnectionBanner
import io.github.daepilot.ui.components.DiagnosticsDialog
import io.github.daepilot.ui.components.DaeGlassCardShape
import io.github.daepilot.ui.components.DaeGlassHeroShape
import io.github.daepilot.ui.components.GlassAction
import io.github.daepilot.ui.components.GlassIconButton
import io.github.daepilot.ui.components.GlassSurface
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.theme.LocalDaeGlassPalette
import kotlinx.coroutines.delay

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
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val probes by viewModel.siteProbes.collectAsStateWithLifecycle()
    val bandwidth by viewModel.bandwidth.collectAsStateWithLifecycle()
    val alerts by viewModel.logAlerts.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    LaunchedEffect(status.running) {
        if (status.running) viewModel.refreshSiteProbes()
    }
    val selectedNode = snapshot.nodes.firstOrNull { it.id == snapshot.selectedNodeId }
    val defaultGroup = remember(snapshot.groups, snapshot.defaultGroupId) {
        snapshot.groups.firstOrNull { it.id == snapshot.defaultGroupId && it.enabled }
    }
    val defaultGroupMembers = remember(defaultGroup, snapshot.nodes) {
        defaultGroup?.nodeIds?.count { id -> snapshot.nodes.any { it.id == id } } ?: 0
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                defaultGroup = defaultGroup,
                defaultGroupMembers = defaultGroupMembers,
                busy = busy,
                setup = setup,
                setupLabel = setupLabel,
                setupLoading = setupLoading,
                onSetup = onSetup,
                onToggle = { viewModel.toggleProxy(!status.running) },
                onOpenNodes = onOpenNodes,
                connection = connection,
                onRefreshExit = viewModel::refreshExit,
            )
        }
        item {
            // Anomalies outrank telemetry, and the action row sits above them: the
            // floating dock already owns the bottom of the screen, so anything
            // actionable has to be reachable without scrolling for it.
            if (alerts.total > 0) {
                AlertsCard(alerts = alerts, onOpenLogs = onOpenLogs)
            }
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
        item { ResourceCard(status) }
        item {
            SiteCard(
                probes = probes,
                running = status.running,
                onTest = viewModel::testSite,
            )
        }
        item {
            BandwidthCard(
                state = bandwidth,
                proxyRunning = status.running,
                onTest = viewModel::testBandwidth,
            )
        }
        item {
            SignalCard(status = status, bootRequested = snapshot.settings.bootStart)
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
        Column(modifier = Modifier.weight(1f)) {
            Text("DaePilot", style = MaterialTheme.typography.titleLarge)
            Text(
                "${BuildConfig.VERSION_NAME.substringBefore("-debug")} · dae $daeVersion",
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
    defaultGroup: OutboundGroup?,
    defaultGroupMembers: Int,
    busy: Boolean,
    setup: Boolean,
    setupLabel: String?,
    setupLoading: Boolean,
    onSetup: (() -> Unit)?,
    onToggle: () -> Unit,
    onOpenNodes: () -> Unit,
    connection: ConnectionState,
    onRefreshExit: () -> Unit,
) {
    val running = status.running
    // After a reboot the status read has to re-establish the su session first;
    // "syncing" keeps the card honest instead of flashing "已停止".
    val syncing = status.mode == "checking" || status.mode == "unknown"
    val title = when {
        running -> "运行中"
        syncing -> "正在同步"
        status.mode == "no-root" -> "需要 Root"
        status.mode == "module-missing" -> "需要模块"
        else -> "已停止"
    }
    val detail = when {
        running -> when {
            defaultGroup != null ->
                "${defaultGroup.policy.label} · $defaultGroupMembers 个节点"
            selectedNode != null ->
                "${selectedNode.protocol.label} · ${selectedNode.endpoint?.host ?: "—"}"
            else -> "透明代理已接管全部流量"
        }
        syncing -> "正在读取 daemon 的实时运行状态"
        status.mode == "no-root" -> "在 Magisk / KernelSU 中授权后继续"
        status.mode == "module-missing" -> "安装 dae 数据面模块后继续"
        else -> "选择节点，点按电源开始接管"
    }
    val eyebrow = when {
        running -> "已连接"
        setup -> "待设置"
        syncing -> "同步中"
        else -> "待机"
    }
    val palette = LocalDaeGlassPalette.current
    val tone = when {
        running -> palette.success
        setup -> palette.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    GlassSurface(
        shape = DaeGlassHeroShape,
        contentPadding = PaddingValues(20.dp),
        spacing = 16.dp,
        onClick = onOpenNodes,
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
                if (running) "TProxy" else "本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The card navigates; the whole panel is clickable, so the cue goes on
            // the eyebrow row rather than on a second, nested button.
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(15.dp),
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
                }
            }
            Spacer(Modifier.width(14.dp))
            PowerOrb(
                running = running,
                compact = setup,
                busy = busy,
                onToggle = onToggle,
            )
        }
        if (running) {
            // The exit used to be a strip pinned above the header, which read as a
            // second title bar. It belongs with the state it describes.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f),
                )
                ConnectionBanner(
                    state = connection,
                    running = true,
                    onRefresh = onRefreshExit,
                    embedded = true,
                )
            }
        }
    }
}

@Composable
private fun SetupPill(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val dark = LocalDaeGlassPalette.current.isDark
    Surface(
        onClick = onClick,
        enabled = !loading,
        shape = CircleShape,
        color = if (dark) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (dark) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
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
    compact: Boolean,
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
    val glowAlpha = if (running) 0.55f + 0.35f * breath else 0f
    val outerSize = if (compact) 68.dp else 88.dp
    val innerSize = if (compact) 54.dp else 64.dp
    val iconSize = if (compact) 22.dp else 26.dp
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
    val contentColor = if (running) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val edgeColor = if (running) {
        // A lit orb still needs the specular rim, or it reads as flat plastic.
        Color.White.copy(alpha = if (palette.isDark) 0.42f else 0.65f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.40f)
    }
    Box(
        modifier = Modifier.size(outerSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(outerSize)
                .background(
                    Brush.radialGradient(
                        listOf(palette.glow.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(innerSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (compact) 8.dp else 16.dp,
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
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (running) 0.30f else 0.16f),
                                Color.Transparent,
                            ),
                        ),
                        CircleShape,
                    ),
            )
            if (pressed) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = 0.12f)),
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (running) "停止代理" else "启动代理",
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun ResourceCard(status: DaemonStatus) {
    val running = status.running
    val palette = LocalDaeGlassPalette.current
    // startedAt is absolute, so tick locally: StateFlow dedups equal statuses and
    // the uptime would otherwise sit still while CPU and RSS stay flat.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val uptimeSeconds = if (running && status.startedAt > 0) {
        ((now - status.startedAt) / 1000).coerceAtLeast(0)
    } else {
        0L
    }
    val cpu = status.cpuPercent
    val cpuTone = when {
        !running -> MaterialTheme.colorScheme.outline
        cpu == null -> MaterialTheme.colorScheme.onSurfaceVariant
        cpu >= 60 -> MaterialTheme.colorScheme.error
        cpu >= 25 -> palette.warning
        else -> palette.success
    }
    val memKb = status.rssKb
    val memTone = when {
        !running -> MaterialTheme.colorScheme.outline
        memKb <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        memKb >= 256L * 1024 -> MaterialTheme.colorScheme.error
        memKb >= 128L * 1024 -> palette.warning
        else -> palette.success
    }
    val uptimeTone = if (running) palette.success else MaterialTheme.colorScheme.outline

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        spacing = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResourceStat(
                label = "CPU",
                value = if (running) Format.percent(cpu) else "—",
                tone = cpuTone,
                modifier = Modifier.weight(1f),
            )
            ResourceStat(
                label = "内存",
                value = if (running) Format.memory(memKb) else "—",
                tone = memTone,
                modifier = Modifier.weight(1f),
            )
            ResourceStat(
                label = "运行时长",
                value = if (running) compactUptime(uptimeSeconds) else "—",
                tone = uptimeTone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Two units at most so the value fits a third of the card width. */
private fun compactUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}天${hours}时"
        hours > 0 -> "${hours}时${minutes}分"
        minutes > 0 -> "${minutes}分"
        else -> "${seconds}秒"
    }
}

@Composable
private fun ResourceStat(
    label: String,
    value: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(palette.glassTop, palette.glassBottom)))
            .border(1.dp, palette.borderBottom, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
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
                    .background(tone),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SiteCard(
    probes: SiteProbeState,
    running: Boolean,
    onTest: (String) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 15.dp),
        spacing = 9.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "站点连通",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (running) "每 2 分钟自动刷新 · 点按图标单独测试" else "代理未启用 · 点击图标可测直连",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (probes.lastRunAt > 0) {
                Text(
                    Format.relativeTime(probes.lastRunAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DomainTargets.home.chunked(4).forEach { rowTargets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowTargets.forEach { target ->
                    SiteTile(
                        target = target,
                        result = probes.results[target.host],
                        testing = probes.testing.contains(target.host),
                        onClick = { onTest(target.host) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!running) {
            Text(
                "启用代理后再测，才能确认节点是否真正连通。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertsCard(alerts: LogAlerts, onOpenLogs: () -> Unit) {
    val palette = LocalDaeGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        spacing = 10.dp,
        onClick = onOpenLogs,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = palette.warning,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("运行告警", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本次运行 · 点按查看日志",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (alerts.dnsUpstreamFailures > 0) {
            val hosts = alerts.failingUpstreams
                .map { it.substringAfter("://").substringBefore("/").substringBefore(":") }
                .distinct()
                .joinToString("、")
            AlertRow(
                label = "DNS 上游失败",
                count = alerts.dnsUpstreamFailures,
                detail = hosts.ifBlank { null },
                tone = palette.warning,
            )
        }
        if (alerts.udpQueueDrops > 0) {
            AlertRow(
                label = "UDP 队列丢包",
                count = alerts.udpQueueDrops,
                detail = "节点转发队列饱和",
                tone = palette.warning,
            )
        }
        if (alerts.datapathAnomalies > 0) {
            AlertRow(
                label = "数据面异常",
                count = alerts.datapathAnomalies,
                detail = "连接路由元数据冲突",
                tone = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AlertRow(label: String, count: Int, detail: String?, tone: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("$count 次", style = MaterialTheme.typography.titleSmall, color = tone)
    }
}

@Composable
private fun SiteTile(
    target: DomainTarget,
    result: DomainTestResult?,
    testing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    val brand = siteBrand(target)
    val brandIcon = brand.icon
    val reachable = result?.reachable
    val latencyMs = result?.latencyMs
    val tone = when {
        testing || reachable == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !reachable -> MaterialTheme.colorScheme.error
        // A full DNS + TCP + TLS handshake through an overseas node routinely
        // lands around 0.5-1.5 s, so only flag genuinely slow sites.
        latencyMs != null && latencyMs < 1000 -> palette.success
        else -> palette.warning
    }
    val latency = when {
        testing -> "测试中"
        reachable == null -> "未测"
        !reachable -> "失败"
        latencyMs != null -> "$latencyMs ms"
        else -> "未测"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !testing, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(brand.background)
                .border(
                    0.75.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                    RoundedCornerShape(13.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                testing -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = brand.foreground,
                )
                brandIcon != null -> Icon(
                    brandIcon,
                    contentDescription = null,
                    tint = brand.foreground,
                    modifier = Modifier.size(21.dp),
                )
                else -> Text(
                    brand.glyph.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = brand.foreground,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            target.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            latency,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            maxLines = 1,
        )
    }
}

private data class SiteBrand(
    val background: Color,
    val foreground: Color,
    val glyph: String? = null,
    val icon: ImageVector? = null,
)

@Composable
private fun siteBrand(target: DomainTarget): SiteBrand = when (target.host) {
    "google.com" -> SiteBrand(Color(0xFFF8F9FA), Color(0xFF4285F4), glyph = "G")
    "youtube.com" -> SiteBrand(Color(0xFFFF0000), Color.White, icon = Icons.Outlined.PlayArrow)
    "github.com" -> SiteBrand(Color(0xFF24292F), Color.White, icon = Icons.Outlined.Code)
    "telegram.org" -> SiteBrand(Color(0xFF229ED9), Color.White, icon = Icons.AutoMirrored.Outlined.Send)
    "chatgpt.com" -> SiteBrand(Color(0xFF10A37F), Color.White, icon = Icons.Outlined.AutoAwesome)
    "netflix.com" -> SiteBrand(Color(0xFF141414), Color(0xFFE50914), glyph = "N")
    "x.com" -> SiteBrand(Color(0xFF000000), Color.White, glyph = "X")
    "baidu.com" -> SiteBrand(Color(0xFF2932E1), Color.White, glyph = "百")
    else -> SiteBrand(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
        glyph = target.label.take(1),
    )
}

@Composable
private fun BandwidthCard(
    state: BandwidthState,
    proxyRunning: Boolean,
    onTest: () -> Unit,
) {
    val result = state.result
    val failed = result != null && result.downloadMbps == null && result.uploadMbps == null
    val error = result?.error
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        spacing = 11.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "代理带宽",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        state.running -> "正在测试下载与上传…"
                        result == null -> "点按测速：5 MB 下载 / 2 MB 上传"
                        proxyRunning -> "最近一次经代理测速"
                        state.proxied -> "最近一次经代理测速（代理已停止）"
                        else -> "最近一次为直连测速"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onTest,
                enabled = !state.running,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (state.running) "测速中" else "测速",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BandwidthValue(
                label = "下载",
                value = result?.downloadMbps,
                icon = Icons.Outlined.ArrowDownward,
                modifier = Modifier.weight(1f),
            )
            BandwidthValue(
                label = "上传",
                value = result?.uploadMbps,
                icon = Icons.Outlined.ArrowUpward,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.testedAt > 0) {
                Text(
                    "测试于 ${Format.relativeTime(state.testedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (result != null && (result.downloadBytes > 0 || result.uploadBytes > 0)) {
                Text(
                    "↓ ${Format.bytes(result.downloadBytes)} · ↑ ${Format.bytes(result.uploadBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (failed && error != null) {
            Text(
                "测速失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BandwidthValue(
    label: String,
    value: Double?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                Format.mbps(value),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Mbps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun SignalCard(status: DaemonStatus, bootRequested: Boolean) {
    val rootKnown = status.mode != "checking" && status.mode != "unknown"
    val rootReady = rootKnown && status.mode != "no-root"
    val bootValue = when {
        status.bootEnabled -> "已开启"
        bootRequested -> "待生效"
        else -> "关闭"
    }
    GlassSurface(
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 13.dp),
        spacing = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalCell(
                label = "权限",
                value = when {
                    !rootKnown -> "同步中"
                    rootReady -> "正常"
                    else -> "未授权"
                },
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
                            LocalDaeGlassPalette.current.success
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
