package io.github.daepilot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RootSetupCard(
    requesting: Boolean,
    message: String?,
    onRequest: () -> Unit,
) {
    SetupNotice(
        title = "需要 root 权限",
        body = "dae 依赖 eBPF 特权加载。授权后会直接弹出系统 root 确认框，" +
            "成功拿到权限时自动安装控制模块。",
        error = message?.takeIf { !it.startsWith("已获得") },
        actionLabel = if (requesting) "等待授权…" else "请求 root 权限",
        loading = requesting,
        onAction = onRequest,
    )
}

@Composable
fun ModuleSetupCard(
    installing: Boolean,
    onInstall: () -> Unit,
) {
    SetupNotice(
        title = "尚未安装模块",
        body = "root 已就绪。安装内置模块会写入 dae 二进制、GeoSite 数据与后台守护脚本，" +
            "完成后需重启一次。",
        error = null,
        actionLabel = if (installing) "正在安装…" else "安装模块",
        loading = installing,
        onAction = onInstall,
    )
}

@Composable
private fun SetupNotice(
    title: String,
    body: String,
    error: String?,
    actionLabel: String,
    loading: Boolean,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onAction,
                enabled = !loading,
                shape = MaterialTheme.shapes.small,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(actionLabel)
                }
            }
        }
    }
}
