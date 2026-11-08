package io.github.daepilot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.displayName
import java.util.UUID

private val policyOrder = listOf(
    GroupPolicy.MIN_MOVING_AVG,
    GroupPolicy.RANDOM,
    GroupPolicy.MIN,
    GroupPolicy.MIN_AVG10,
    GroupPolicy.FIXED,
)

private fun policyHint(policy: GroupPolicy): String = when (policy) {
    GroupPolicy.MIN_MOVING_AVG -> "每次连接选当前最快成员，故障自动切换"
    GroupPolicy.RANDOM -> "每条连接随机选，把流量摊到多条线"
    GroupPolicy.MIN -> "按上次测得的延迟选最快"
    GroupPolicy.MIN_AVG10 -> "按最近 10 次平均延迟选最快"
    GroupPolicy.FIXED -> "只用成员列表第一个，其余备用"
}

/** Shared create/edit sheet for outbound groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditorSheet(
    snapshot: AppSnapshot,
    existing: OutboundGroup?,
    initialNodeIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (OutboundGroup) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var policy by remember(existing) {
        mutableStateOf(existing?.policy ?: GroupPolicy.MIN_MOVING_AVG)
    }
    var selected by remember(existing, initialNodeIds) {
        mutableStateOf(existing?.nodeIds?.toSet() ?: initialNodeIds)
    }
    var nodeQuery by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subscriptionNames = remember(snapshot.subscriptions) {
        snapshot.subscriptions.associate { it.id to it.name }
    }
    val visibleNodes = remember(snapshot.nodes, nodeQuery) {
        val needle = nodeQuery.trim()
        if (needle.isEmpty()) {
            snapshot.nodes
        } else {
            snapshot.nodes.filter { node ->
                node.displayName(node.subscriptionId?.let { subscriptionNames[it] })
                    .contains(needle, ignoreCase = true) ||
                    node.endpoint?.host?.contains(needle, ignoreCase = true) == true
            }
        }
    }

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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (existing == null) "新建分组" else "编辑分组",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "多个节点合成一组，分流规则可以按组走",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text("分组名") },
                placeholder = { Text("香港组") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                isError = error != null,
                supportingText = {
                    Text(error ?: "支持中文；dae 标识符会自动生成")
                },
            )

            Text("组内策略", style = MaterialTheme.typography.labelMedium)
            policyOrder.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { policy = option }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = policy == option, onClick = { policy = option })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            policyHint(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "成员（已选 ${selected.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { selected = selected + visibleNodes.map { it.id } },
                    enabled = visibleNodes.isNotEmpty(),
                ) { Text("全选") }
                TextButton(
                    onClick = { selected = emptySet() },
                    enabled = selected.isNotEmpty(),
                ) { Text("清空") }
            }

            OutlinedTextField(
                value = nodeQuery,
                onValueChange = { nodeQuery = it },
                label = { Text("搜索节点") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )

            if (snapshot.nodes.isEmpty()) {
                Text(
                    "节点库为空，请先在订阅页导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (visibleNodes.isEmpty()) {
                Text(
                    "没有匹配的节点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            visibleNodes.forEach { node ->
                val label = node.displayName(node.subscriptionId?.let { subscriptionNames[it] })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (node.id in selected) {
                                selected - node.id
                            } else {
                                selected + node.id
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = node.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + node.id else selected - node.id
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        node.endpoint?.host?.let { host ->
                            Text(
                                host,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("取消") }
                Button(
                    onClick = {
                        val trimmed = name.trim()
                        val duplicate = snapshot.groups.any {
                            it.id != existing?.id && it.name.equals(trimmed, ignoreCase = true)
                        }
                        when {
                            trimmed.isEmpty() -> error = "请填写分组名"
                            trimmed.length > 24 -> error = "分组名最多 24 个字符"
                            duplicate -> error = "已有同名分组"
                            selected.isEmpty() -> error = "至少选择一个节点"
                            else -> onSave(
                                OutboundGroup(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    name = trimmed,
                                    key = existing?.key.orEmpty(),
                                    policy = policy,
                                    nodeIds = snapshot.nodes.map { it.id }
                                        .filter { it in selected },
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("保存") }
            }
        }
    }
}
