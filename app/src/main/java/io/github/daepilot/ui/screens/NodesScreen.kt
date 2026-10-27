package io.github.daepilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.EmptyState
import io.github.daepilot.ui.components.LatencyBadge
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader

@Composable
fun NodesScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    busy: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Node?>(null) }

    val byId = remember(snapshot.subscriptions) { snapshot.subscriptions.associateBy { it.id } }
    val filtered = remember(snapshot.nodes, query, filter) {
        snapshot.nodes.filter { node ->
            val matchesFilter = when (filter) {
                null -> true
                "fav" -> node.favorite
                else -> node.subscriptionId == filter
            }
            val q = query.trim()
            val matchesQuery = q.isEmpty() ||
                node.name.contains(q, ignoreCase = true) ||
                node.endpoint?.host?.contains(q, ignoreCase = true) == true
            matchesFilter && matchesQuery
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "节点",
            subtitle = "${snapshot.nodes.size} 个节点 · ${snapshot.subscriptions.size} 个订阅",
            modifier = Modifier.padding(horizontal = ScreenEdge),
            actions = {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(
                    onClick = viewModel::testAllNodes,
                    enabled = snapshot.nodes.isNotEmpty() && !busy,
                ) { Text("测延迟") }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenEdge),
            placeholder = { Text("搜索名称或服务器") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenEdge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("全部") },
            )
            FilterChip(
                selected = filter == "fav",
                onClick = { filter = "fav" },
                label = { Text("收藏") },
            )
            snapshot.subscriptions.forEach { sub ->
                FilterChip(
                    selected = filter == sub.id,
                    onClick = { filter = sub.id },
                    label = {
                        Text(
                            "${sub.name} (${sub.nodeCount})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        if (snapshot.nodes.any { it.protocol.isUdpOnly }) {
            Text(
                "QUIC 节点使用 ICMP 测延迟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = ScreenEdge, end = ScreenEdge, bottom = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ScreenEdge),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
                item {
                    AutoSelectRow(
                        selected = snapshot.selectedNodeId == null,
                        onClick = { viewModel.selectNode(null) },
                    )
                }

                if (filtered.isEmpty()) {
                    item {
                        RowDivider()
                        EmptyState(
                            icon = Icons.Outlined.Dns,
                            title = if (snapshot.nodes.isEmpty()) "节点库为空" else "没有匹配的节点",
                            message = if (snapshot.nodes.isEmpty()) {
                                "从订阅或分享链接导入节点。"
                            } else {
                                "换个关键词或筛选条件。"
                            },
                        )
                    }
                }

                items(filtered, key = { it.id }) { node ->
                    RowDivider()
                    NodeRow(
                        node = node,
                        subscriptionName = node.subscriptionId?.let { byId[it]?.name },
                        selected = node.id == snapshot.selectedNodeId,
                        onSelect = { viewModel.selectNode(node.id) },
                        onTest = { viewModel.testNode(node.id) },
                        onFavorite = { viewModel.setFavorite(node.id, !node.favorite) },
                        onDelete = { deleteTarget = node },
                    )
                }
            }
    }

    deleteTarget?.let { node ->
        ConfirmDialog(
            title = "删除本地节点",
            message = "确定删除「${node.name}」？订阅中的节点会在下次更新时重新出现。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { viewModel.deleteNode(node.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun AutoSelectRow(selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text("自动选择", style = MaterialTheme.typography.bodyLarge)
            Text(
                "按最低延迟自动切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    subscriptionName: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (node.protocol != NodeProtocol.UNKNOWN) {
                    Text(
                        node.protocol.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                buildString {
                    node.endpoint?.host?.let { append(it) }
                    node.endpoint?.port?.let { append(":$it") }
                    subscriptionName?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }.ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LatencyBadge(node.latencyMs, tag = if (node.protocol.isUdpOnly) "ICMP" else null)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("测试延迟") },
                    leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                    onClick = {
                        menuOpen = false
                        onTest()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (node.favorite) "取消收藏" else "收藏") },
                    leadingIcon = {
                        Icon(
                            if (node.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onFavorite()
                    },
                )
                DropdownMenuItem(
                    text = { Text("复制节点链接") },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(node.link))
                    },
                )
                if (node.subscriptionId == null) {
                    DropdownMenuItem(
                        text = { Text("删除本地节点") },
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
