package io.github.daepilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.core.network.DomainTestReport
import io.github.daepilot.core.network.DomainTestResult
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.GlassIconButton
import io.github.daepilot.ui.components.GlassSurface
import io.github.daepilot.ui.components.ListGroup
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

@Composable
fun NetworkTestScreen(
    viewModel: AppViewModel,
    status: DaemonStatus,
    onBack: () -> Unit,
) {
    val state by viewModel.domainTest.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = ScreenEdge,
            end = ScreenEdge,
            top = 4.dp,
            bottom = 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "连接测试",
                subtitle = "检查当前网络能否访问常用站点",
                leading = {
                    GlassIconButton(
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                    )
                },
            )
        }
        item { NetworkContextCard(status) }
        item {
            RunButton(
                running = state.running,
                hasResult = state.report != null,
                onClick = viewModel::runDomainTests,
            )
        }
        if (state.report == null && !state.running) {
            item {
                Text(
                    "将测试 Google / YouTube / GitHub / Telegram / 百度，并读取出口 IP 与地区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                )
            }
        }
        state.report?.let { report ->
            item { ReportSummary(report) }
            item {
                ListGroup {
                    report.results.forEachIndexed { index, result ->
                        DomainResultRow(result)
                        if (index != report.results.lastIndex) RowDivider()
                    }
                }
            }
            item {
                Text(
                    "测试只验证 HTTPS 可达性，不代表流媒体解锁；结果取决于当前节点和运营商网络。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
                )
            }
        }
        state.error?.let { error ->
            item {
                GlassSurface(shape = RoundedCornerShape(18.dp)) {
                    Text(
                        "测试失败：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkContextCard(status: DaemonStatus) {
    val text = when {
        status.running -> "代理已启用 · ${status.node ?: "节点未知"}"
        status.mode == "no-root" ->
            "未授权 Root（不影响测试）；测试使用系统当前网络"
        status.mode == "module-missing" ->
            "Magisk 模块未安装；测试使用系统当前网络"
        else -> "代理未启用；本次测试会直连"
    }
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        spacing = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunButton(
    running: Boolean,
    hasResult: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = !running,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在测试", style = MaterialTheme.typography.labelLarge)
            } else {
                Text(
                    if (hasResult) "再测一次" else "开始测试",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ReportSummary(report: DomainTestReport) {
    val palette = LocalDaeGlassPalette.current
    val total = report.results.size
    val ok = report.results.count { it.reachable }
    val googleOrYoutubeDown = report.results.any {
        !it.reachable && (it.target.label == "Google" || it.target.label == "YouTube")
    }
    val baiduUp = report.results.any {
        it.reachable && it.target.label == "Baidu"
    }
    val tone = when {
        ok == total -> palette.success
        ok > 0 -> palette.warning
        else -> MaterialTheme.colorScheme.error
    }
    val title = when {
        ok == total -> "全部可达"
        ok == 0 -> "全部不可达"
        else -> "$ok / $total 个站点可达"
    }
    val hint = when {
        ok == total -> "当前网络可以访问全部测试站点。"
        googleOrYoutubeDown && baiduUp -> buildString {
            append("国内站点正常，Google / YouTube 不可达。")
            val exit = report.exit
            if (exit != null && exit.country == "CN") {
                append(
                    if (report.proxyRunning) {
                        "节点出口位于 CN，可能没有国际出口，建议更换节点。"
                    } else {
                        "当前出口位于 CN；若代理已启用，说明节点可能没有国际出口，建议更换节点。"
                    },
                )
            } else {
                append("请检查节点出口或分流规则。")
            }
        }
        ok == 0 -> "请检查网络、DNS 或代理状态后重试。"
        else -> "部分站点不可达，请查看下方的失败原因。"
    }

    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(18.dp),
        spacing = 8.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
            Spacer(Modifier.width(9.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = tone)
            Spacer(Modifier.weight(1f))
            if (report.proxyRunning) {
                Text(
                    "经代理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val exit = report.exit
        if (exit != null) {
            Text(
                buildString {
                    append("出口 ${exit.ip}")
                    if (exit.country.isNotBlank()) append(" · ${exit.country}")
                    if (exit.colo.isNotBlank()) append(" · ${exit.colo}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!report.node.isNullOrBlank()) {
            Text(
                "节点 ${report.node}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DomainResultRow(result: DomainTestResult) {
    val palette = LocalDaeGlassPalette.current
    val tone = if (result.reachable) palette.success else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram(result),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.target.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                result.target.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            if (result.reachable) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tone,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (result.reachable) {
                "${result.detail.removePrefix("HTTP ")} · ${result.latencyMs} ms"
            } else {
                result.detail
            },
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun monogram(result: DomainTestResult): String = when (result.target.label) {
    "Google" -> "G"
    "YouTube" -> "Y"
    "GitHub" -> "GH"
    "Telegram" -> "T"
    "Baidu" -> "B"
    else -> result.target.label.take(1)
}
