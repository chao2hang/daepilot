package io.github.daepilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.Subscription
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.EmptyState
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.components.SectionCard
import io.github.daepilot.ui.components.TrafficBar

@Composable
fun SubscriptionsScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    busy: Boolean,
) {
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Subscription?>(null) }

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
                title = "订阅",
                subtitle = "${snapshot.subscriptions.size} 个订阅 · ${snapshot.nodes.size} 个节点",
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 4.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "粘贴节点链接")
                    }
                    Spacer(Modifier.width(2.dp))
                    FilledTonalIconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加订阅")
                    }
                },
            )
        }

        if (snapshot.subscriptions.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Subscriptions,
                    title = "还没有订阅",
                    message = "添加订阅链接，或直接粘贴节点分享链接。\n支持 Clash YAML、sing-box JSON 与 base64 列表。",
                    primaryLabel = "添加订阅",
                    onPrimary = { showAdd = true },
                    secondaryLabel = "粘贴节点链接",
                    onSecondary = { showImport = true },
                )
            }
        }

        items(snapshot.subscriptions, key = { it.id }) { subscription ->
            SubscriptionCard(
                subscription = subscription,
                busy = busy,
                onRefresh = { viewModel.refreshSubscription(subscription.id) },
                onToggleEnabled = {
                    viewModel.setSubscriptionEnabled(subscription.id, !subscription.enabled)
                },
                onToggleAuto = {
                    viewModel.setSubscriptionAutoUpdate(subscription.id, !subscription.autoUpdate)
                },
                onDelete = { deleteTarget = subscription },
            )
        }

        item {
            SectionCard(
                title = "支持的格式",
                subtitle = "解析后统一渲染为 dae 节点",
            ) {
                Text(
                    "分享链接与 base64 列表（ss、vmess、vless、trojan、hysteria2、tuic 等）\n" +
                        "Clash / Clash.Meta YAML 的 proxies\n" +
                        "sing-box JSON 的 outbounds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "SSR、WireGuard、Snell 暂不支持，导入时会单独提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAdd) {
        AddSubscriptionDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, url ->
                viewModel.addSubscription(name, url)
                showAdd = false
            },
        )
    }

    if (showImport) {
        ImportLinksDialog(
            onDismiss = { showImport = false },
            onConfirm = { prefix, text ->
                viewModel.importLinks(text, prefix)
                showImport = false
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除订阅",
            message = "将删除「${target.name}」及其 ${target.nodeCount} 个节点。已应用的配置不会自动回滚。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { viewModel.deleteSubscription(target.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    busy: Boolean,
    onRefresh: () -> Unit,
    onToggleEnabled: () -> Unit,
    onToggleAuto: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    subscription.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hostOf(subscription.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("${subscription.nodeCount} 个节点 · 更新于 ")
                        append(Format.relativeTime(subscription.lastUpdatedAt))
                        append(if (subscription.autoUpdate) " · 自动更新" else " · 手动更新")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = subscription.enabled, onCheckedChange = { onToggleEnabled() })
        }

        subscription.traffic?.let { TrafficBar(it) }

        if (!subscription.lastError.isNullOrBlank()) {
            Text(
                subscription.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即更新")
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (subscription.autoUpdate) "改为手动更新" else "开启自动更新") },
                        onClick = {
                            menuOpen = false
                            onToggleAuto()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除订阅") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text("订阅 URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = url.trim()
                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                        error = "请输入 http(s) 订阅链接"
                    } else {
                        onConfirm(name.trim(), trimmed)
                    }
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportLinksDialog(
    onDismiss: () -> Unit,
    onConfirm: (prefix: String, text: String) -> Unit,
) {
    var prefix by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴节点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("名称前缀（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("分享链接 / Clash YAML / sing-box JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    minLines = 6,
                    maxLines = 10,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isBlank()) error = "请粘贴内容"
                    else onConfirm(prefix.trim(), text)
                },
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: url.take(48)
