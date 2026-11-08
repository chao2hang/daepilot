package io.github.daepilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.displayName
import io.github.daepilot.core.network.SpeedTestResult
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.DaeChip
import io.github.daepilot.ui.components.EmptyState
import io.github.daepilot.ui.components.GlassSearchField
import io.github.daepilot.ui.components.GlassSurface
import io.github.daepilot.ui.components.GroupEditorSheet
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

private enum class NodeSort(val label: String) {
    DEFAULT("默认顺序"),
    LATENCY("延迟最低"),
    DOWNLOAD("下载最快"),
    UPLOAD("上传最快"),
}

private enum class NodesTab { NODES, GROUPS }

@Composable
fun NodesScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    busy: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(NodeSort.DEFAULT) }
    var sortMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Node?>(null) }
    var pendingSpeedNode by remember { mutableStateOf<Node?>(null) }
    var showSpeedNotice by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(NodesTab.NODES) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedForGroup by remember { mutableStateOf(setOf<String>()) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<OutboundGroup?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<OutboundGroup?>(null) }
    var showDefaultPicker by remember { mutableStateOf(false) }
    val speedTest by viewModel.speedTest.collectAsStateWithLifecycle()
    val requestSpeed: (Node) -> Unit = { node ->
        if (viewModel.speedTestNeedsNotice()) {
            pendingSpeedNode = node
            showSpeedNotice = true
        } else {
            viewModel.testNodeSpeed(node.id)
        }
    }

    val byId = remember(snapshot.subscriptions) { snapshot.subscriptions.associateBy { it.id } }
    val customGroups = remember(snapshot.groups) {
        snapshot.groups.filter { it.enabled && !it.builtIn }
    }
    val groupMembers = remember(snapshot.groups, snapshot.nodes) {
        val byNode = snapshot.nodes.associateBy { it.id }
        snapshot.groups.associate { group ->
            group.id to group.nodeIds.mapNotNull { byNode[it] }
        }
    }
    val defaultGroup = remember(snapshot.defaultGroupId, customGroups) {
        customGroups.firstOrNull { it.id == snapshot.defaultGroupId }
    }
    val defaultNode = remember(snapshot.selectedNodeId, snapshot.nodes) {
        snapshot.nodes.firstOrNull { it.id == snapshot.selectedNodeId }
    }
    val nodeLabel: (Node) -> String = { node ->
        node.displayName(node.subscriptionId?.let { byId[it]?.name })
    }
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

    val displayed = remember(filtered, sort, speedTest.results) {
        when (sort) {
            NodeSort.DEFAULT -> filtered
            NodeSort.LATENCY -> filtered.sortedWith(
                compareBy<Node> { it.latencyMs ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() },
            )
            NodeSort.DOWNLOAD -> filtered.sortedWith(
                compareByDescending<Node> { speedTest.results[it.id]?.downloadMbps ?: -1.0 }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE },
            )
            NodeSort.UPLOAD -> filtered.sortedWith(
                compareByDescending<Node> { speedTest.results[it.id]?.uploadMbps ?: -1.0 }
                    .thenBy { it.latencyMs ?: Int.MAX_VALUE },
            )
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (tab == NodesTab.NODES) "节点" else "分组",
            subtitle = if (tab == NodesTab.NODES) {
                "${snapshot.nodes.size} 个节点 · ${snapshot.subscriptions.size} 个订阅"
            } else {
                "${customGroups.size} 个分组 · ${snapshot.nodes.size} 个节点"
            },
            modifier = Modifier.padding(horizontal = ScreenEdge),
            actions = {
                when {
                    tab == NodesTab.NODES && selectionMode -> {
                        TextButton(onClick = {
                            selectionMode = false
                            selectedForGroup = emptySet()
                        }) { Text("取消") }
                    }
                    tab == NodesTab.NODES -> {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Sort,
                                    contentDescription = "排序",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                NodeSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        leadingIcon = if (option == sort) {
                                            { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                                        } else {
                                            null
                                        },
                                        onClick = {
                                            sort = option
                                            sortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = viewModel::testAllNodes,
                            enabled = snapshot.nodes.isNotEmpty() && !busy,
                        ) { Text("测延迟") }
                        TextButton(onClick = {
                            selectionMode = true
                            selectedForGroup = emptySet()
                        }) { Text("选择") }
                    }
                    else -> {
                        TextButton(onClick = {
                            editingGroup = null
                            showGroupEditor = true
                        }) { Text("新建") }
                    }
                }
            },
        )

        if (tab == NodesTab.NODES) {
            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索名称或服务器",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenEdge),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenEdge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DaeChip(
                selected = tab == NodesTab.NODES,
                onClick = {
                    tab = NodesTab.NODES
                    selectionMode = false
                    selectedForGroup = emptySet()
                },
                label = "节点",
            )
            DaeChip(
                selected = tab == NodesTab.GROUPS,
                onClick = {
                    tab = NodesTab.GROUPS
                    selectionMode = false
                    selectedForGroup = emptySet()
                },
                label = "分组 ${customGroups.size}",
            )
            if (tab == NodesTab.NODES) {
                DaeChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = "全部",
                )
                DaeChip(
                    selected = filter == "fav",
                    onClick = { filter = "fav" },
                    label = "收藏",
                )
                snapshot.subscriptions.forEach { sub ->
                    DaeChip(
                        selected = filter == sub.id,
                        onClick = { filter = sub.id },
                        label = "${sub.name} (${sub.nodeCount})",
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }

        if (tab == NodesTab.NODES) {
            if (snapshot.nodes.any { it.protocol.isUdpOnly }) {
                Text(
                    "QUIC 节点按其服务端口测握手延迟，不再依赖 ping",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = ScreenEdge, end = ScreenEdge, bottom = 6.dp),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ScreenEdge),
                    contentPadding = PaddingValues(top = 2.dp, bottom = ScreenBottom),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        DefaultOutboundCard(
                            group = defaultGroup,
                            groupMembers = defaultGroup
                                ?.let { groupMembers[it.id]?.size } ?: 0,
                            node = defaultNode,
                            nodeLabel = defaultNode?.let(nodeLabel),
                            onClick = { showDefaultPicker = true },
                        )
                    }

                    if (filtered.isEmpty()) {
                        item {
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

                    items(displayed, key = { it.id }) { node ->
                        NodeRow(
                            node = node,
                            subscriptionName = node.subscriptionId?.let { byId[it]?.name },
                            selected = defaultGroup == null && node.id == snapshot.selectedNodeId,
                            speedResult = speedTest.results[node.id],
                            speedRunning = speedTest.runningNodeId == node.id,
                            speedBusy = speedTest.runningNodeId != null,
                            interactive = speedTest.runningNodeId == null,
                            selectionMode = selectionMode,
                            selectedForGroup = node.id in selectedForGroup,
                            onToggleGroupSelect = {
                                selectedForGroup = if (node.id in selectedForGroup) {
                                    selectedForGroup - node.id
                                } else {
                                    selectedForGroup + node.id
                                }
                            },
                            onSelect = { viewModel.selectNode(node.id) },
                            onTest = { viewModel.testNode(node.id) },
                            onSpeedTest = { requestSpeed(node) },
                            onFavorite = { viewModel.setFavorite(node.id, !node.favorite) },
                            onToggleSkipCert = {
                                viewModel.setNodeSkipCertVerify(node.id, !node.skipCertVerify)
                            },
                            onDelete = { deleteTarget = node },
                        )
                    }
                }
                if (selectionMode) {
                    SelectionBar(
                        count = selectedForGroup.size,
                        onCreate = {
                            editingGroup = null
                            showGroupEditor = true
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = ScreenEdge, end = ScreenEdge, bottom = ScreenBottom),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ScreenEdge),
                contentPadding = PaddingValues(top = 2.dp, bottom = ScreenBottom),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (customGroups.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Dns,
                            title = "还没有分组",
                            message = "把多个节点合成一组，分流规则就能按组走。",
                        )
                    }
                }
                items(customGroups, key = { it.id }) { group ->
                    val members = groupMembers[group.id].orEmpty()
                    GroupCard(
                        group = group,
                        members = members,
                        isDefault = group.id == snapshot.defaultGroupId,
                        nodeLabel = nodeLabel,
                        onClick = {
                            if (members.isNotEmpty()) viewModel.selectGroup(group.id)
                        },
                        onEdit = {
                            editingGroup = group
                            showGroupEditor = true
                        },
                        onDelete = { deleteGroupTarget = group },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            editingGroup = null
                            showGroupEditor = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("新建分组")
                    }
                }
            }
        }
    }

    if (showGroupEditor) {
        GroupEditorSheet(
            snapshot = snapshot,
            existing = editingGroup,
            initialNodeIds = selectedForGroup,
            onDismiss = { showGroupEditor = false },
            onSave = { group ->
                viewModel.saveGroup(group)
                showGroupEditor = false
                selectionMode = false
                selectedForGroup = emptySet()
                tab = NodesTab.GROUPS
            },
        )
    }

    deleteGroupTarget?.let { group ->
        ConfirmDialog(
            title = "删除分组",
            message = "确定删除「${group.name}」？正在使用该组的规则需要先改到其他出口。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteGroup(group.id)
                deleteGroupTarget = null
            },
            onDismiss = { deleteGroupTarget = null },
        )
    }

    if (showDefaultPicker) {
        DefaultOutboundSheet(
            snapshot = snapshot,
            groups = customGroups,
            groupMembers = groupMembers,
            nodeLabel = nodeLabel,
            onSelectAuto = {
                showDefaultPicker = false
                viewModel.selectAuto()
            },
            onSelectGroup = { group ->
                showDefaultPicker = false
                viewModel.selectGroup(group.id)
            },
            onSelectNode = { node ->
                showDefaultPicker = false
                viewModel.selectNode(node.id)
            },
            onDismiss = { showDefaultPicker = false },
        )
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

    if (showSpeedNotice) {
        ConfirmDialog(
            title = "节点测速",
            message = "测速期间会临时把代理切换到该节点（约 15 秒），完成后恢复原来的节点。" +
                "\n\n下载约 5MB、上传约 2MB，需要代理正在运行。",
            confirmLabel = "开始测速",
            onConfirm = {
                viewModel.acknowledgeSpeedTestNotice()
                showSpeedNotice = false
                pendingSpeedNode?.let { viewModel.testNodeSpeed(it.id) }
                pendingSpeedNode = null
            },
            onDismiss = {
                showSpeedNotice = false
                pendingSpeedNode = null
            },
        )
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .border(
                1.5.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                },
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun DefaultOutboundCard(
    group: OutboundGroup?,
    groupMembers: Int,
    node: Node?,
    nodeLabel: String?,
    onClick: () -> Unit,
) {
    val summary = when {
        group != null -> "${group.name} · $groupMembers 个节点 · ${group.policy.label}"
        node != null -> "${nodeLabel ?: node.name} · 单节点"
        else -> "自动 · 全部节点按延迟选最快"
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        spacing = 0.dp,
        elevation = 6.dp,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "默认出口",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        spacing = 0.dp,
        elevation = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已选 $count 个节点",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onCreate, enabled = count > 0, shape = CircleShape) {
                Text("创建分组")
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    subscriptionName: String?,
    selected: Boolean,
    speedResult: SpeedTestResult?,
    speedRunning: Boolean,
    speedBusy: Boolean,
    interactive: Boolean,
    selectionMode: Boolean = false,
    selectedForGroup: Boolean = false,
    onToggleGroupSelect: () -> Unit = {},
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onSpeedTest: () -> Unit,
    onFavorite: () -> Unit,
    onToggleSkipCert: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // The stored name embeds the subscription prefix; the list shows the source
    // separately, so recover the node's own remark (vmess keeps it in `ps`).
    val displayName = remember(node.link, node.name, subscriptionName) {
        node.displayName(subscriptionName)
    }
    val marked = if (selectionMode) selectedForGroup else selected
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        spacing = 0.dp,
        elevation = 6.dp,
        tone = if (marked) MaterialTheme.colorScheme.primary else null,
        onClick = when {
            selectionMode -> onToggleGroupSelect
            interactive -> onSelect
            else -> null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectMark(selected = marked)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (node.protocol != NodeProtocol.UNKNOWN) {
                        ProtocolTag(node.protocol.label)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    SourceTag(label = subscriptionName ?: "本地节点")
                    if (node.skipCertVerify) {
                        Spacer(Modifier.width(6.dp))
                        WarningTag(label = "跳过证书校验")
                    }
                    val endpoint = buildString {
                        node.endpoint?.host?.let { append(it) }
                        node.endpoint?.port?.let { append(":$it") }
                    }
                    if (endpoint.isNotEmpty()) {
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // lastTestedAt is the only trace a probe left behind: the error
                    // text is not persisted, so "tested and failed" has to be told
                    // apart from "never tested" by the timestamp.
                    if (node.latencyMs != null || node.lastTestedAt > 0) {
                        LatencyPill(
                            millis = node.latencyMs,
                            failed = node.latencyMs == null,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                val speedFailed = speedResult != null &&
                    speedResult.downloadMbps == null &&
                    speedResult.uploadMbps == null
                val speedText = when {
                    speedRunning -> "正在测速…"
                    speedFailed -> "测速失败"
                    speedResult != null ->
                        "↓ ${Format.mbps(speedResult.downloadMbps)} · " +
                            "↑ ${Format.mbps(speedResult.uploadMbps)} Mbps"
                    else -> null
                }
                if (speedText != null) {
                    Text(
                        speedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (speedFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!selectionMode) {
                if (speedRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = interactive) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "更多",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("测速") },
                            leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                            enabled = !speedBusy,
                            onClick = {
                                menuOpen = false
                                onSpeedTest()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("测试延迟") },
                            leadingIcon = { Icon(Icons.Outlined.Timer, null) },
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
                            text = {
                                Text(
                                    if (node.skipCertVerify) {
                                        "恢复证书校验"
                                    } else {
                                        "跳过证书校验"
                                    },
                                )
                            },
                            leadingIcon = if (node.skipCertVerify) {
                                { Icon(Icons.Rounded.Check, null) }
                            } else {
                                null
                            },
                            onClick = {
                                menuOpen = false
                                onToggleSkipCert()
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
    }
}

@Composable
private fun SourceTag(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

@Composable
private fun WarningTag(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProtocolTag(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp, top = 2.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = CircleShape,
            )
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun LatencyPill(
    millis: Int?,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    val (text, color) = when {
        millis != null && millis < 100 -> "$millis ms" to palette.success
        millis != null && millis < 250 -> "$millis ms" to palette.warning
        millis != null -> "$millis ms" to MaterialTheme.colorScheme.error
        failed -> "不可用" to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun GroupCard(
    group: OutboundGroup,
    members: List<Node>,
    isDefault: Boolean,
    nodeLabel: (Node) -> String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val empty = members.isEmpty()
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        spacing = 0.dp,
        elevation = 6.dp,
        tone = if (isDefault) MaterialTheme.colorScheme.primary else null,
        onClick = if (empty) null else onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectMark(selected = isDefault)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDefault) {
                        Text(
                            "默认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append(group.policy.label)
                        append(" · ")
                        append(if (empty) "空组" else "${members.size} 个节点")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (empty) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!empty) {
                    Text(
                        members.joinToString(" · ") { nodeLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "分组操作",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑分组") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除分组") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultOutboundSheet(
    snapshot: AppSnapshot,
    groups: List<OutboundGroup>,
    groupMembers: Map<String, List<Node>>,
    nodeLabel: (Node) -> String,
    onSelectAuto: () -> Unit,
    onSelectGroup: (OutboundGroup) -> Unit,
    onSelectNode: (Node) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("默认出口", style = MaterialTheme.typography.headlineSmall)
            Text(
                "默认出口同时是分流规则的兜底出口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            OptionRow(
                selected = snapshot.defaultGroupId == null && snapshot.selectedNodeId == null,
                title = "自动",
                subtitle = "全部节点按延迟选最快",
                onClick = onSelectAuto,
            )
            if (groups.isNotEmpty()) {
                Text(
                    "分组",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                groups.forEach { group ->
                    val count = groupMembers[group.id].orEmpty().size
                    OptionRow(
                        selected = snapshot.defaultGroupId == group.id,
                        title = group.name,
                        subtitle = buildString {
                            append(group.policy.label)
                            append(" · ")
                            append(if (count == 0) "空组" else "$count 个节点")
                        },
                        enabled = count > 0,
                        onClick = { onSelectGroup(group) },
                    )
                }
            }
            if (snapshot.nodes.isNotEmpty()) {
                Text(
                    "单个节点",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                snapshot.nodes.forEach { node ->
                    OptionRow(
                        selected = snapshot.defaultGroupId == null &&
                            snapshot.selectedNodeId == node.id,
                        title = nodeLabel(node),
                        subtitle = listOfNotNull(
                            node.endpoint?.host,
                            node.latencyMs?.let { "$it ms" },
                        ).joinToString(" · "),
                        onClick = { onSelectNode(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
