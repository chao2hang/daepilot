package io.github.daepilot.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.LogLine
import io.github.daepilot.ui.LogUploadState
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.EmptyState
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader

private val levelOptions = listOf(
    null to "全部",
    "debug" to "Debug",
    "info" to "Info",
    "warn" to "Warn",
    "error" to "Error",
)

@Composable
fun LogsScreen(
    viewModel: AppViewModel,
    logs: List<LogLine>,
    upload: LogUploadState,
    onBack: () -> Unit,
) {
    var level by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { viewModel.startLogStream() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopLogStream() }
    }

    val filtered = remember(logs, level, query) {
        val q = query.trim()
        logs.filter { line ->
            (level == null || line.level == level) &&
                (q.isEmpty() || line.raw.contains(q, ignoreCase = true))
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "日志",
            subtitle = "${filtered.size} / ${logs.size} 行 · 2.5 秒刷新",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
            modifier = Modifier.padding(horizontal = ScreenEdge),
            actions = {
                IconButton(
                    onClick = viewModel::uploadLogs,
                    enabled = !upload.uploading,
                ) {
                    if (upload.uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = "上传日志")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导出 / 分享") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = {
                                menuOpen = false
                                shareLogs(context, viewModel.exportLogs())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空日志") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                            onClick = {
                                menuOpen = false
                                showClearConfirm = true
                            },
                        )
                    }
                }
            },
        )

        Text(
            "仅在你点击上传时发送；会附带脱敏后的配置与最近错误，仍可能包含节点地址和访问域名。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenEdge, vertical = 2.dp),
        )

        when {
            upload.uploading -> UploadBanner(
                text = "正在上传…",
                tone = MaterialTheme.colorScheme.primary,
                loading = true,
            )
            upload.lastId != null -> UploadBanner(
                text = "已上传 · 编号 ${upload.lastId}",
                tone = MaterialTheme.colorScheme.primary,
                onCopy = {
                    clipboard.setText(AnnotatedString(upload.lastId))
                },
                onDismiss = viewModel::clearUploadStatus,
            )
            upload.error != null -> UploadBanner(
                text = upload.error,
                tone = MaterialTheme.colorScheme.error,
                onDismiss = viewModel::clearUploadStatus,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenEdge, vertical = 8.dp),
            placeholder = { Text("搜索日志") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenEdge),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levelOptions.forEach { (value, label) ->
                FilterChip(
                    selected = level == value,
                    onClick = { level = value },
                    label = { Text(label) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenEdge, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "自动滚动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = autoScroll,
                onCheckedChange = { autoScroll = it },
                modifier = Modifier.padding(start = 6.dp),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                "dae.log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.Article,
                title = if (logs.isEmpty()) "暂无日志" else "没有匹配的日志",
                message = if (logs.isEmpty()) {
                    "启动 dae 后，数据面日志会显示在这里。"
                } else {
                    "换个等级或关键词。"
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = ScreenEdge,
                    end = ScreenEdge,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
            ) {
                items(filtered) { line ->
                    LogRow(line)
                }
            }
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空日志",
            message = "将清空 dae 的日志文件，操作不可撤销。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = viewModel::clearLogs,
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun UploadBanner(
    text: String,
    tone: androidx.compose.ui.graphics.Color,
    loading: Boolean = false,
    onCopy: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenEdge, vertical = 6.dp),
        shape = MaterialTheme.shapes.small,
        color = tone.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = tone,
                )
                Box(Modifier.width(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = tone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制编号",
                        modifier = Modifier.size(16.dp),
                        tint = tone,
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(16.dp),
                        tint = tone,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.level) {
        "error" -> MaterialTheme.colorScheme.error
        "warn" -> MaterialTheme.colorScheme.tertiary
        "debug" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.level.take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = line.raw,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}

private fun shareLogs(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "DaePilot 日志")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "导出日志"))
}
