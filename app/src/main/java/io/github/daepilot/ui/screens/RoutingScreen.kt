package io.github.daepilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.config.DaeConfigValidator
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.components.SectionCard

private val directTargets = listOf("proxy", "direct", "block", "must_direct")
private val groupNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

@Composable
fun RoutingScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    busy: Boolean,
) {
    var showPreview by remember { mutableStateOf(false) }
    var ruleEditor by remember { mutableStateOf<RoutingRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var deleteRule by remember { mutableStateOf<RoutingRule?>(null) }
    var deleteGroup by remember { mutableStateOf<OutboundGroup?>(null) }
    var domesticDns by remember(snapshot.dns) { mutableStateOf(snapshot.dns.domesticUpstream) }
    var foreignDns by remember(snapshot.dns) { mutableStateOf(snapshot.dns.foreignUpstream) }
    var useDomestic by remember(snapshot.dns) { mutableStateOf(snapshot.dns.useDomesticForCn) }

    val customGroups = remember(snapshot.groups) {
        snapshot.groups.filter { it.enabled && !it.builtIn }
    }
    val targets = remember(customGroups) {
        (directTargets + customGroups.map { it.name }).distinct()
    }

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
                title = "分流",
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 4.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    TextButton(onClick = { showPreview = true }) { Text("预览") }
                },
            )
        }

        item {
            SectionCard(title = "分流模板") {
                RoutingTemplate.entries.forEachIndexed { index, template ->
                    if (index > 0) RowDivider()
                    TemplateRow(
                        template = template,
                        selected = snapshot.template == template,
                        onSelect = { viewModel.setTemplate(template) },
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "自定义规则",
                action = {
                    IconButton(onClick = {
                        ruleEditor = null
                        showRuleEditor = true
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加规则")
                    }
                },
            ) {
                if (snapshot.rules.isEmpty()) {
                    Text(
                        "暂无规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                snapshot.rules.forEachIndexed { index, rule ->
                    if (index > 0) RowDivider()
                    RuleRow(
                        rule = rule,
                        onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                        onEdit = {
                            ruleEditor = rule
                            showRuleEditor = true
                        },
                        onDelete = { deleteRule = rule },
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "自定义分组",
                action = {
                    IconButton(onClick = { showGroupEditor = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建分组")
                    }
                },
            ) {
                Text(
                    "BUILT-IN · PROXY / DIRECT / BLOCK",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                customGroups.forEachIndexed { index, group ->
                    if (index > 0) RowDivider()
                    GroupRow(
                        group = group,
                        memberCount = group.nodeIds.count { id -> snapshot.nodes.any { it.id == id } },
                        onDelete = { deleteGroup = group },
                    )
                }
            }
        }

        item {
            SectionCard(title = "DNS") {
                OutlinedTextField(
                    value = domesticDns,
                    onValueChange = { domesticDns = it },
                    label = { Text("国内上游") },
                    placeholder = { Text("udp://dns.alidns.com:53") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = foreignDns,
                    onValueChange = { foreignDns = it },
                    label = { Text("国外上游") },
                    placeholder = { Text("tcp+udp://dns.google:53") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("大陆域名使用国内 DNS", style = MaterialTheme.typography.bodyMedium)

                    }
                    Switch(checked = useDomestic, onCheckedChange = { useDomestic = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveDns(
                                snapshot.dns.copy(
                                    domesticUpstream = domesticDns.trim(),
                                    foreignUpstream = foreignDns.trim(),
                                    useDomesticForCn = useDomestic,
                                ),
                            )
                        },
                        enabled = domesticDns.isNotBlank() && foreignDns.isNotBlank(),
                        shape = MaterialTheme.shapes.small,
                    ) { Text("保存 DNS") }
                }
            }
        }

        item {
            SectionCard(title = "配置") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::applyConfig,
                        enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("应用配置") }
                    OutlinedButton(
                        onClick = viewModel::reloadConfig,
                        enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("热重载") }
                }
            }
        }
    }

    if (showPreview) {
        ConfigPreviewDialog(
            config = viewModel.previewConfig(),
            onDismiss = { showPreview = false },
        )
    }

    if (showRuleEditor) {
        RuleEditorDialog(
            existing = ruleEditor,
            targets = targets,
            onDismiss = {
                showRuleEditor = false
                ruleEditor = null
            },
            onSave = { expression, outbound ->
                viewModel.saveRule(expression, outbound, ruleEditor?.id)
                showRuleEditor = false
                ruleEditor = null
            },
        )
    }

    if (showGroupEditor) {
        GroupEditorDialog(
            snapshot = snapshot,
            onDismiss = { showGroupEditor = false },
            onSave = {
                viewModel.saveGroup(it)
                showGroupEditor = false
            },
        )
    }

    deleteRule?.let { rule ->
        ConfirmDialog(
            title = "删除规则",
            message = "确定删除规则「${rule.expression}」？",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { viewModel.deleteRule(rule.id) },
            onDismiss = { deleteRule = null },
        )
    }

    deleteGroup?.let { group ->
        ConfirmDialog(
            title = "删除分组",
            message = "确定删除分组「${group.name}」？使用该出口的规则将回退为 direct。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { viewModel.deleteGroup(group.id) },
            onDismiss = { deleteGroup = null },
        )
    }
}

@Composable
private fun TemplateRow(
    template: RoutingTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(template.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleRow(
    rule: RoutingRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                rule.expression,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "→ ${rule.outbound}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "规则操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑规则") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除规则") },
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

@Composable
private fun GroupRow(
    group: OutboundGroup,
    memberCount: Int,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${group.policy.label} · $memberCount 个节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除分组")
        }
    }
}

@Composable
private fun RuleEditorDialog(
    existing: RoutingRule?,
    targets: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var expression by remember(existing) { mutableStateOf(existing?.expression.orEmpty()) }
    var outbound by remember(existing) { mutableStateOf(existing?.outbound ?: targets.firstOrNull() ?: "proxy") }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = expression,
                    onValueChange = {
                        expression = it
                        error = null
                    },
                    label = { Text("匹配条件") },
                    placeholder = { Text("domain(geosite:openai)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    minLines = 3,
                    maxLines = 6,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "可用函数：domain、dip、dport、pname、l4proto 等")
                    },
                )
                Text("出口", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(outbound, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        targets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text(target) },
                                onClick = {
                                    outbound = target
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validation = DaeConfigValidator.validateRule(expression)
                    if (!validation.ok) {
                        error = validation.message
                    } else {
                        onSave(expression.trim(), outbound)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GroupEditorDialog(
    snapshot: AppSnapshot,
    onDismiss: () -> Unit,
    onSave: (OutboundGroup) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(GroupPolicy.MIN_MOVING_AVG) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var policyMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分组") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("分组名（字母/数字/下划线）") },
                    placeholder = { Text("HK") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                Text("选择策略", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { policyMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(policy.label, modifier = Modifier.weight(1f))
                        Text("▾")
                    }
                    DropdownMenu(expanded = policyMenu, onDismissRequest = { policyMenu = false }) {
                        GroupPolicy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    policy = option
                                    policyMenu = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "节点（已选 ${selected.size}）",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (snapshot.nodes.isEmpty()) {
                    Text(
                        "节点库为空，请先在订阅页导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                snapshot.nodes.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (node.id in selected) selected - node.id else selected + node.id
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
                                node.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            node.endpoint?.host?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    when {
                        !groupNamePattern.matches(trimmed) ->
                            error = "分组名需以字母或下划线开头，仅含字母、数字、下划线"
                        selected.isEmpty() -> error = "至少选择一个节点"
                        else -> onSave(
                            OutboundGroup(
                                id = java.util.UUID.randomUUID().toString(),
                                name = trimmed,
                                policy = policy,
                                nodeIds = selected.toList(),
                            ),
                        )
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfigPreviewDialog(config: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("config.dae") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    config,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(config)) }) {
                Text("复制")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
