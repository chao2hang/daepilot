package io.github.daepilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.config.DaeConfigValidator
import io.github.daepilot.core.config.DnsUpstreamBuilder
import io.github.daepilot.core.config.DomainMatch
import io.github.daepilot.core.config.PresetSource
import io.github.daepilot.core.config.RuleBuild
import io.github.daepilot.core.config.RuleBuilder
import io.github.daepilot.core.config.RuleDraft
import io.github.daepilot.core.config.RuleKind
import io.github.daepilot.core.config.UpstreamDraft
import io.github.daepilot.core.config.UpstreamProtocol
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.GroupPolicy
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.daeKey
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.ConfirmDialog
import io.github.daepilot.ui.components.DaeGlassRadius
import io.github.daepilot.ui.components.GlassAction
import io.github.daepilot.ui.components.RowDivider
import io.github.daepilot.ui.components.ScreenBottom
import io.github.daepilot.ui.components.ScreenEdge
import io.github.daepilot.ui.components.ScreenHeader
import io.github.daepilot.ui.components.GroupEditorSheet
import io.github.daepilot.ui.components.SectionCard

private data class OutboundOption(
    val value: String,
    val label: String,
    val section: String,
)

private enum class DnsField { DOMESTIC, FOREIGN }

/** Saves which upstream is open, so the editor sheet survives a tab switch. */
private val DnsFieldSaver = Saver<DnsField?, String>(
    save = { it?.name ?: "" },
    restore = { if (it.isEmpty()) null else DnsField.valueOf(it) },
)

private val RuleDraftSaver = Saver<RuleDraft, List<String>>(
    save = {
        listOf(
            it.kind.name,
            it.domainMatch.name,
            it.domain,
            it.presetSource.name,
            it.preset,
            it.ip,
            it.ports,
            it.portFilter,
            it.advanced,
        )
    },
    restore = {
        RuleDraft(
            kind = RuleKind.valueOf(it[0]),
            domainMatch = DomainMatch.valueOf(it[1]),
            domain = it[2],
            presetSource = PresetSource.valueOf(it[3]),
            preset = it[4],
            ip = it[5],
            ports = it[6],
            portFilter = it[7],
            advanced = it[8],
        )
    },
)

private val UpstreamDraftSaver = Saver<UpstreamDraft, List<String>>(
    save = {
        listOf(
            it.protocol.name,
            it.host,
            it.port,
            it.path,
            it.raw,
            if (it.advanced) "1" else "0",
        )
    },
    restore = {
        UpstreamDraft(
            protocol = UpstreamProtocol.valueOf(it[0]),
            host = it[1],
            port = it[2],
            path = it[3],
            raw = it[4],
            advanced = it[5] == "1",
        )
    },
)

@Composable
fun RoutingScreen(
    viewModel: AppViewModel,
    snapshot: AppSnapshot,
    busy: Boolean,
) {
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }
    // Only the edited rule's id is persisted; the draft inside the sheet saves itself.
    var ruleEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var showRuleEditor by rememberSaveable { mutableStateOf(false) }
    var showGroupEditor by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<OutboundGroup?>(null) }
    var deleteRule by remember { mutableStateOf<RoutingRule?>(null) }
    var deleteGroup by remember { mutableStateOf<OutboundGroup?>(null) }
    var domesticDns by rememberSaveable(snapshot.dns) { mutableStateOf(snapshot.dns.domesticUpstream) }
    var foreignDns by rememberSaveable(snapshot.dns) { mutableStateOf(snapshot.dns.foreignUpstream) }
    var useDomestic by rememberSaveable(snapshot.dns) { mutableStateOf(snapshot.dns.useDomesticForCn) }
    var blockIpv6 by rememberSaveable(snapshot.dns) { mutableStateOf(snapshot.dns.blockIpv6) }
    var dnsEditor by rememberSaveable(stateSaver = DnsFieldSaver) {
        mutableStateOf<DnsField?>(null)
    }
    val ruleEditor = ruleEditorId?.let { id -> snapshot.rules.firstOrNull { it.id == id } }
    val dnsDirty = domesticDns != snapshot.dns.domesticUpstream ||
        foreignDns != snapshot.dns.foreignUpstream ||
        useDomestic != snapshot.dns.useDomesticForCn ||
        blockIpv6 != snapshot.dns.blockIpv6

    val customGroups = remember(snapshot.groups) {
        snapshot.groups.filter { it.enabled && !it.builtIn }
    }
    val outboundOptions = remember(customGroups, snapshot) {
        val defaultGroup = customGroups.firstOrNull { it.id == snapshot.defaultGroupId }
        val defaultLabel = when {
            defaultGroup != null -> "默认出口（${defaultGroup.name}）"
            snapshot.selectedNodeId != null -> "默认出口（单个节点）"
            else -> "默认出口（自动）"
        }
        buildList {
            add(OutboundOption("proxy", defaultLabel, "基础"))
            add(OutboundOption("direct", "direct · 直连", "基础"))
            add(OutboundOption("block", "block · 拒绝", "基础"))
            add(OutboundOption("must_direct", "must_direct · 强制直连", "基础"))
            customGroups.forEach { group ->
                val count = group.nodeIds.count { id -> snapshot.nodes.any { it.id == id } }
                if (count > 0) {
                    add(
                        OutboundOption(
                            value = group.daeKey(),
                            label = "${group.name} · $count 节点",
                            section = "分组",
                        ),
                    )
                }
            }
        }
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
            // Primary flows sit above the fold so none of them starts with a scroll.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassAction(
                    icon = Icons.Outlined.Add,
                    label = "添加规则",
                    onClick = {
                        ruleEditorId = null
                        showRuleEditor = true
                    },
                    modifier = Modifier.weight(1f),
                )
                GlassAction(
                    icon = Icons.Outlined.Dns,
                    label = "DNS 上游",
                    onClick = { dnsEditor = DnsField.DOMESTIC },
                    modifier = Modifier.weight(1f),
                )
                GlassAction(
                    icon = Icons.Outlined.Layers,
                    label = "出口分组",
                    onClick = {
                        editingGroup = null
                        showGroupEditor = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
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
                        ruleEditorId = null
                        showRuleEditor = true
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加规则")
                    }
                },
            ) {
                if (snapshot.rules.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "还没有规则，添加后会按顺序匹配流量",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                ruleEditorId = null
                                showRuleEditor = true
                            },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加规则")
                        }
                    }
                }
                snapshot.rules.forEachIndexed { index, rule ->
                    if (index > 0) RowDivider()
                    RuleRow(
                        rule = rule,
                        outboundText = describeOutbound(rule.outbound, customGroups, snapshot),
                        canMoveUp = index > 0,
                        canMoveDown = index < snapshot.rules.lastIndex,
                        onToggle = { viewModel.setRuleEnabled(rule.id, it) },
                        onMoveUp = { viewModel.moveRule(rule.id, -1) },
                        onMoveDown = { viewModel.moveRule(rule.id, 1) },
                        onDuplicate = { viewModel.duplicateRule(rule.id) },
                        onEdit = {
                            ruleEditorId = rule.id
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
                    IconButton(onClick = {
                        editingGroup = null
                        showGroupEditor = true
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建分组")
                    }
                },
            ) {
                Text(
                    "默认出口同时是分流的兜底出口",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (customGroups.isEmpty()) {
                    Text(
                        "还没有分组，去节点页多选节点即可创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                customGroups.forEachIndexed { index, group ->
                    if (index > 0) RowDivider()
                    GroupRow(
                        group = group,
                        memberCount = group.nodeIds.count { id -> snapshot.nodes.any { it.id == id } },
                        isDefault = group.id == snapshot.defaultGroupId,
                        onEdit = {
                            editingGroup = group
                            showGroupEditor = true
                        },
                        onDelete = { deleteGroup = group },
                    )
                }
            }
        }

        item {
            SectionCard(title = "DNS") {
                DnsUpstreamRow(
                    title = "国内上游",
                    uri = domesticDns,
                    onClick = { dnsEditor = DnsField.DOMESTIC },
                )
                DnsUpstreamRow(
                    title = "国外上游",
                    uri = foreignDns,
                    onClick = { dnsEditor = DnsField.FOREIGN },
                )
                DnsSwitchRow(
                    title = "大陆域名使用国内 DNS",
                    checked = useDomestic,
                    onCheckedChange = { useDomestic = it },
                )
                DnsSwitchRow(
                    title = "阻止海外 IPv6",
                    description = "避免 IPv4-only 节点卡在 AAAA 查询，推荐开启",
                    checked = blockIpv6,
                    onCheckedChange = { blockIpv6 = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dnsDirty) {
                        Text(
                            "有未保存的更改",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.saveDns(
                                snapshot.dns.copy(
                                    domesticUpstream = domesticDns.trim(),
                                    foreignUpstream = foreignDns.trim(),
                                    useDomesticForCn = useDomestic,
                                    blockIpv6 = blockIpv6,
                                ),
                            )
                        },
                        enabled = dnsDirty && domesticDns.isNotBlank() && foreignDns.isNotBlank(),
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
        // Rendering walks every node link, so it happens off the composition thread
        // and only while the dialog is open.
        LaunchedEffect(snapshot) { previewText = viewModel.previewConfig() }
        previewText?.let { config ->
            ConfigPreviewDialog(
                config = config,
                onDismiss = {
                    showPreview = false
                    previewText = null
                },
            )
        }
    }

    if (showRuleEditor) {
        RuleEditorSheet(
            existing = ruleEditor,
            targets = outboundOptions,
            onDismiss = {
                showRuleEditor = false
                ruleEditorId = null
            },
            onSave = { expression, outbound ->
                viewModel.saveRule(expression, outbound, ruleEditor?.id)
                showRuleEditor = false
                ruleEditorId = null
            },
        )
    }

    if (showGroupEditor) {
        GroupEditorSheet(
            snapshot = snapshot,
            existing = editingGroup,
            onDismiss = { showGroupEditor = false },
            onSave = {
                viewModel.saveGroup(it)
                showGroupEditor = false
            },
        )
    }

    dnsEditor?.let { field ->
        DnsUpstreamSheet(
            title = if (field == DnsField.DOMESTIC) "国内 DNS 上游" else "国外 DNS 上游",
            current = if (field == DnsField.DOMESTIC) domesticDns else foreignDns,
            onDismiss = { dnsEditor = null },
            onSave = { uri ->
                if (field == DnsField.DOMESTIC) domesticDns = uri else foreignDns = uri
                dnsEditor = null
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
    outboundText: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val parsed = remember(rule.expression) { RuleBuilder.parse(rule.expression) }
    val title = remember(rule.expression) { RuleBuilder.describe(rule.expression) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = if (parsed == null) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (parsed != null) {
                Text(
                    rule.expression,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "→ $outboundText",
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
                    text = { Text("上移") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                    enabled = canMoveUp,
                    onClick = {
                        menuOpen = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text("下移") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                    enabled = canMoveDown,
                    onClick = {
                        menuOpen = false
                        onMoveDown()
                    },
                )
                DropdownMenuItem(
                    text = { Text("复制规则") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                )
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
    isDefault: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = MaterialTheme.typography.bodyLarge)
                if (isDefault) {
                    Text(
                        "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                group.policy.label + " · " + if (memberCount == 0) "空组" else "$memberCount 个节点",
                style = MaterialTheme.typography.bodySmall,
                color = if (memberCount == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑分组")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除分组")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    existing: RoutingRule?,
    targets: List<OutboundOption>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var draft by rememberSaveable(existing, stateSaver = RuleDraftSaver) {
        mutableStateOf(initialRuleDraft(existing))
    }
    var outbound by rememberSaveable(existing) {
        mutableStateOf(
            existing?.outbound?.takeIf { value ->
                targets.any { it.value == value }
            } ?: targets.firstOrNull()?.value ?: "proxy",
        )
    }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    var targetMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val built = remember(draft) { RuleBuilder.build(draft) }
    val shownError = error ?: built.error?.takeIf { ruleDraftHasInput(draft) }
    val update: (RuleDraft) -> Unit = {
        draft = it
        error = null
    }
    val confirm = {
        val result = RuleBuilder.build(draft)
        if (!result.ok) {
            error = result.error ?: "规则无效"
        } else {
            val validation = DaeConfigValidator.validateRule(result.expression)
            if (!validation.ok) {
                error = validation.message
            } else {
                onSave(result.expression, outbound)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = DaeGlassRadius, topEnd = DaeGlassRadius),
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (existing == null) "添加规则" else "编辑规则",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "选择类型并填写内容，会自动生成 dae 规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            RuleKindPicker(selected = draft.kind, onSelect = { update(draft.copy(kind = it)) })

            when (draft.kind) {
                RuleKind.DOMAIN -> DomainRuleFields(draft, update)
                RuleKind.IP -> IpRuleFields(draft, update)
                RuleKind.PRESET -> PresetRuleFields(draft, update)
                RuleKind.PORT -> PortRuleFields(draft, update)
                RuleKind.ADVANCED -> AdvancedRuleFields(draft, update)
            }

            if (draft.kind == RuleKind.DOMAIN ||
                draft.kind == RuleKind.IP ||
                draft.kind == RuleKind.PRESET
            ) {
                OutlinedTextField(
                    value = draft.portFilter,
                    onValueChange = { update(draft.copy(portFilter = it)) },
                    label = { Text("限定端口（可选）") },
                    placeholder = { Text("443, 1000-2000") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = { Text("留空匹配所有端口；多个用逗号分隔") },
                )
            }

            RulePreview(built = built, error = shownError, outbound = outbound)

            Text("出口", style = MaterialTheme.typography.labelMedium)
            val selectedLabel = targets.firstOrNull { it.value == outbound }?.label ?: outbound
            Box {
                OutlinedButton(
                    onClick = { targetMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(selectedLabel, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                    var section: String? = null
                    targets.forEach { target ->
                        if (target.section != section) {
                            section = target.section
                            Text(
                                target.section,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(target.label) },
                            onClick = {
                                outbound = target.value
                                targetMenu = false
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("取消") }
                Button(
                    onClick = confirm,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(if (existing == null) "添加规则" else "保存修改")
                }
            }
        }
    }
}

private fun initialRuleDraft(existing: RoutingRule?): RuleDraft {
    if (existing == null) return RuleDraft()
    return RuleBuilder.parse(existing.expression)
        ?: RuleDraft(kind = RuleKind.ADVANCED, advanced = existing.expression)
}

private fun describeOutbound(
    outbound: String,
    groups: List<OutboundGroup>,
    snapshot: AppSnapshot,
): String = when (outbound) {
    "direct" -> "direct · 直连"
    "block" -> "block · 拒绝"
    "must_direct" -> "must_direct · 不劫持 DNS 直连"
    "proxy" -> {
        val group = groups.firstOrNull { it.id == snapshot.defaultGroupId }
        when {
            group != null -> "默认出口（${group.name}）"
            snapshot.selectedNodeId != null -> "默认出口（单个节点）"
            else -> "默认出口（自动）"
        }
    }
    else -> {
        val group = groups.firstOrNull { it.daeKey() == outbound || it.name == outbound }
        if (group != null) {
            val count = group.nodeIds.count { id -> snapshot.nodes.any { it.id == id } }
            "${group.name} · $count 节点"
        } else {
            "$outbound（未找到）"
        }
    }
}

private fun ruleDraftHasInput(draft: RuleDraft): Boolean = when (draft.kind) {
    RuleKind.DOMAIN -> draft.domain.isNotBlank()
    RuleKind.IP -> draft.ip.isNotBlank()
    RuleKind.PRESET -> draft.preset.isNotBlank()
    RuleKind.PORT -> draft.ports.isNotBlank()
    RuleKind.ADVANCED -> draft.advanced.isNotBlank()
}

private val RuleKind.icon: ImageVector
    get() = when (this) {
        RuleKind.DOMAIN -> Icons.Outlined.Language
        RuleKind.IP -> Icons.Outlined.Public
        RuleKind.PRESET -> Icons.Outlined.Layers
        RuleKind.PORT -> Icons.Outlined.Numbers
        RuleKind.ADVANCED -> Icons.Outlined.Code
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleKindPicker(selected: RuleKind, onSelect: (RuleKind) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuleKind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(kind.label) },
                leadingIcon = {
                    Icon(
                        imageVector = if (selected == kind) Icons.Outlined.Check else kind.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DomainRuleFields(draft: RuleDraft, onDraft: (RuleDraft) -> Unit) {
    Text("匹配方式", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DomainMatch.entries.forEach { mode ->
            FilterChip(
                selected = draft.domainMatch == mode,
                onClick = { onDraft(draft.copy(domainMatch = mode)) },
                label = { Text(mode.label) },
            )
        }
    }
    val label: String
    val placeholder: String
    val hint: String
    when (draft.domainMatch) {
        DomainMatch.SUFFIX -> {
            label = "域名"
            placeholder = "openai.com, chatgpt.com"
            hint = "后缀匹配会同时命中所有子域名；粘贴链接会自动提取域名"
        }
        DomainMatch.FULL -> {
            label = "完整域名"
            placeholder = "dns.google"
            hint = "只匹配完全相同的域名"
        }
        DomainMatch.KEYWORD -> {
            label = "关键词"
            placeholder = "facebook"
            hint = "命中域名中包含该关键词的连接"
        }
        DomainMatch.REGEX -> {
            label = "正则表达式"
            placeholder = "\\.goo.*\\.com\$"
            hint = "RE2 语法，不需要加引号"
        }
    }
    OutlinedTextField(
        value = draft.domain,
        onValueChange = { onDraft(draft.copy(domain = it)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        minLines = 1,
        maxLines = 4,
        singleLine = draft.domainMatch == DomainMatch.REGEX,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        supportingText = { Text(hint) },
    )
}

@Composable
private fun IpRuleFields(draft: RuleDraft, onDraft: (RuleDraft) -> Unit) {
    OutlinedTextField(
        value = draft.ip,
        onValueChange = { onDraft(draft.copy(ip = it)) },
        label = { Text("IP 或 CIDR") },
        placeholder = { Text("1.2.3.4, 10.0.0.0/8, 2001:db8::/32") },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        supportingText = { Text("支持 IPv4、IPv6 和网段，多个用逗号或换行分隔") },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRuleFields(draft: RuleDraft, onDraft: (RuleDraft) -> Unit) {
    Text("清单类型", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetSource.entries.forEach { source ->
            FilterChip(
                selected = draft.presetSource == source,
                onClick = { onDraft(draft.copy(presetSource = source)) },
                label = { Text(source.label) },
            )
        }
    }
    OutlinedTextField(
        value = draft.preset,
        onValueChange = { onDraft(draft.copy(preset = it)) },
        label = { Text("清单代码") },
        placeholder = {
            Text(if (draft.presetSource == PresetSource.GEOSITE) "openai, google" else "cn, private")
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        supportingText = {
            Text(
                if (draft.presetSource == PresetSource.GEOSITE) {
                    "dae 内置 geosite 清单，只填代码，例如 cn、geolocation-!cn"
                } else {
                    "dae 内置 geoip 清单，只填代码，例如 cn、private、us"
                },
            )
        },
    )
    Text(
        "常用清单",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val suggestions = if (draft.presetSource == PresetSource.GEOSITE) {
            RuleBuilder.geositeSuggestions
        } else {
            RuleBuilder.geoipSuggestions
        }
        suggestions.forEach { code ->
            SuggestionChip(
                onClick = { onDraft(draft.copy(preset = code)) },
                label = { Text(code) },
            )
        }
    }
}

@Composable
private fun PortRuleFields(draft: RuleDraft, onDraft: (RuleDraft) -> Unit) {
    OutlinedTextField(
        value = draft.ports,
        onValueChange = { onDraft(draft.copy(ports = it)) },
        label = { Text("目标端口") },
        placeholder = { Text("443, 80, 1000-2000") },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        supportingText = { Text("单个端口、逗号分隔或端口段") },
    )
}

@Composable
private fun AdvancedRuleFields(draft: RuleDraft, onDraft: (RuleDraft) -> Unit) {
    OutlinedTextField(
        value = draft.advanced,
        onValueChange = { onDraft(draft.copy(advanced = it)) },
        label = { Text("dae 表达式") },
        placeholder = { Text("domain(geosite:openai) && !dport(443)") },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        minLines = 3,
        maxLines = 8,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        supportingText = {
            Text("支持 domain / dip / dport / l4proto / ipversion 等函数，可用 &&、||、! 组合")
        },
    )
}

@Composable
private fun RulePreview(built: RuleBuild, error: String?, outbound: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "规则预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "→ $outbound",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    built.expression.ifBlank { "填写后自动生成" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (built.ok) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Switch row that toggles from anywhere, with one merged switch for TalkBack. */
@Composable
private fun DnsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsUpstreamRow(title: String, uri: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    DnsUpstreamBuilder.describe(uri),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    uri,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DnsUpstreamSheet(
    title: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by rememberSaveable(stateSaver = UpstreamDraftSaver) {
        mutableStateOf(DnsUpstreamBuilder.parse(current))
    }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val built = remember(draft) { DnsUpstreamBuilder.build(draft) }
    val shownError = error ?: built.error?.takeIf {
        if (draft.advanced) draft.raw.isNotBlank() else draft.host.isNotBlank()
    }
    val update: (UpstreamDraft) -> Unit = {
        draft = it
        error = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = DaeGlassRadius, topEnd = DaeGlassRadius),
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "选预设或手动填写，支持 DoH / DoT / UDP / TCP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            Text("常用预设", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DnsUpstreamBuilder.presets.forEach { preset ->
                    FilterChip(
                        selected = !draft.advanced && built.ok && built.uri == preset.uri,
                        onClick = { update(DnsUpstreamBuilder.parse(preset.uri)) },
                        label = { Text(preset.name) },
                    )
                }
            }

            Text("连接方式", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UpstreamProtocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = !draft.advanced && draft.protocol == protocol,
                        onClick = {
                            val next = if (draft.advanced) {
                                val parsed = DnsUpstreamBuilder.parse(draft.raw)
                                if (!parsed.advanced) parsed.copy(protocol = protocol) else {
                                    draft.copy(advanced = false, protocol = protocol)
                                }
                            } else {
                                draft.copy(protocol = protocol)
                            }
                            update(
                                next.copy(
                                    path = if (protocol == UpstreamProtocol.DOH && next.path.isBlank()) {
                                        "/dns-query"
                                    } else {
                                        next.path
                                    },
                                ),
                            )
                        },
                        label = { Text(protocol.label) },
                    )
                }
                FilterChip(
                    selected = draft.advanced,
                    onClick = {
                        update(
                            draft.copy(
                                advanced = true,
                                raw = draft.raw.ifBlank { built.uri },
                            ),
                        )
                    },
                    label = { Text("原始 URL") },
                )
            }

            if (draft.advanced) {
                OutlinedTextField(
                    value = draft.raw,
                    onValueChange = { update(draft.copy(raw = it)) },
                    label = { Text("上游 URL") },
                    placeholder = { Text("https://dns.example/dns-query") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = { Text("支持 dae 的所有上游协议，例如 h3://") },
                )
            } else {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { update(draft.copy(host = it)) },
                    label = { Text("服务器") },
                    placeholder = { Text("223.5.5.5 或 dns.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = draft.port,
                    onValueChange = { update(draft.copy(port = it)) },
                    label = { Text("端口（可选）") },
                    placeholder = { Text("默认 ${draft.protocol.defaultPort}") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (draft.protocol == UpstreamProtocol.DOH) {
                    OutlinedTextField(
                        value = draft.path,
                        onValueChange = { update(draft.copy(path = it)) },
                        label = { Text("DoH 路径") },
                        placeholder = { Text("/dns-query") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "将写入配置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (shownError != null) {
                        Text(
                            shownError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            built.uri.ifBlank { "填写服务器地址后自动生成" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (built.ok) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("取消") }
                Button(
                    onClick = {
                        val result = DnsUpstreamBuilder.build(draft)
                        if (!result.ok) {
                            error = result.error ?: "DNS 上游无效"
                        } else {
                            onSave(result.uri)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("使用此上游") }
            }
        }
    }
}

@Composable
private fun ConfigPreviewDialog(config: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    // No snackbar route is exposed by the viewModel for a plain copy, so the button
    // itself carries the acknowledgement until the dialog closes.
    var copied by rememberSaveable { mutableStateOf(false) }
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
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(config))
                copied = true
            }) {
                Text(if (copied) "已复制到剪贴板" else "复制")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
