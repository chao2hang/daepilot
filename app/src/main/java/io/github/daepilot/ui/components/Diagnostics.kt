package io.github.daepilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.model.CompatibilityReport
import io.github.daepilot.core.model.DaemonStatus
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

@Composable
fun DiagnosticsDialog(
    report: CompatibilityReport?,
    status: DaemonStatus?,
    loading: Boolean,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断") },
        text = {
            val scroll = rememberScrollState()
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (status != null) {
                        NetworkSection(status)
                        HorizontalDivider()
                        Text("兼容性", style = MaterialTheme.typography.titleSmall)
                    }
                    if (loading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在检查…", modifier = Modifier.padding(start = 12.dp))
                        }
                    } else if (report == null) {
                        Text("尚未运行兼容性检查。检查需要 root 权限。")
                    } else {
                        CheckRow("Root 权限", report.rootAvailable, if (report.rootAvailable) "已授权" else "未授权")
                        CheckRow(
                            "Magisk 模块",
                            report.moduleInstalled,
                            if (report.moduleInstalled) "已安装" else "未安装",
                        )
                        CheckRow(
                            "dae 二进制",
                            report.daeBinaryPresent,
                            report.daeVersion ?: if (report.daeBinaryPresent) "存在" else "缺失",
                        )
                        CheckRow(
                            "内核版本",
                            report.kernelOk,
                            "${report.kernelRelease.ifBlank { "未知" }}（建议 5.15+）",
                        )
                        CheckRow("BTF", report.btfPresent, "/sys/kernel/btf/vmlinux")
                        CheckRow("cgroup v2", report.cgroup2Mounted, "cgroup connect 钩子")
                        report.pnameRouting?.let { live ->
                            CheckRow(
                                "pname 进程分流",
                                live,
                                if (live) "cgroup 钩子已挂载" else "钩子挂载失败，分应用绕过不生效",
                            )
                        }
                        CheckRow("BPF JIT", report.bpfJitEnabled, "eBPF 执行")
                        CheckRow("可用内存", report.memoryOk, "${report.memAvailableMb} MB（最低 150 MB）")
                        CheckRow(
                            "SELinux",
                            true,
                            report.selinuxMode.ifBlank { "未知" },
                        )
                        if (report.conflicts.isNotEmpty()) {
                            HorizontalDivider()
                            Text("冲突", style = MaterialTheme.typography.titleSmall)
                            report.conflicts.forEach {
                                Text(
                                    "· $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                "请先停止 box / Clash 等其它透明代理，二者不能同时接管流量。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        report.note
                            ?.takeIf { it.isNotBlank() && it != status?.lastError }
                            ?.let { note ->
                                HorizontalDivider()
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            if (scroll.canScrollForward) {
                // The panel clips hard at 460 dp; the fade says the list continues.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ),
                        ),
                )
            }
            }
        },
        confirmButton = {
            TextButton(onClick = onRun) { Text("重新检查") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun NetworkSection(status: DaemonStatus) {
    Text("网络", style = MaterialTheme.typography.titleSmall)
    val iface = listOfNotNull(
        status.wan?.takeIf { it.isNotBlank() },
        status.wanIp?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { "—" }
    NetworkRow("接口", iface)
    val lan = status.lan?.takeIf { it.isNotBlank() }
    if (lan != null || status.lanHook == "failed") {
        val failed = status.lanHook == "failed"
        NetworkRow(
            "热点共享",
            if (failed) {
                "接口绑定失败，仅本机代理"
            } else {
                listOfNotNull(lan, status.lanClients?.let { "$it 台设备" }).joinToString(" · ")
            },
            error = failed,
        )
    }
    NetworkRow("DNS", status.dns?.takeIf { it.isNotBlank() } ?: "—")
    NetworkRow(
        "DNS 模式",
        if (status.dnsMode == "proxy") "国内解析经代理（蜂窝）" else "直连",
    )
    NetworkRow(
        "IPv6",
        if (status.ipv6Mode == "blocked") "已阻止（节点 IPv4）" else "允许",
    )
    NetworkRow("节点", status.node?.takeIf { it.isNotBlank() } ?: "—")
    status.bypassUidCount?.let { count ->
        NetworkRow("应用绕过", "$count 个应用已标记")
    }
    status.lastError?.takeIf { it.isNotBlank() }?.let {
        NetworkRow("最近错误", it, error = true)
    }
}

@Composable
private fun NetworkRow(label: String, value: String, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (ok) LocalDaeGlassPalette.current.success else MaterialTheme.colorScheme.error,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
