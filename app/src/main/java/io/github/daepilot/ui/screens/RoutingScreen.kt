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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Public
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.config.DaeConfigValidator
import io.github.daepilot.core.config.DomainMatch
import io.github.daepilot.core.config.PresetSource
import io.github.daepilot.core.config.RuleBuild
import io.github.daepilot.core.config.RuleBuilder
import io.github.daepilot.core.config.RuleDraft
import io.github.daepilot.core.config.RuleKind
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
    var previewText by remember { mutableStateOf<String?>(null) }
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
                                ruleEditor = null
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
                "→ ${outboundLabel(rule.outbound)}",
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    existing: RoutingRule?,
    targets: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var draft by remember(existing) { mutableStateOf(initialRuleDraft(existing)) }
    var outbound by remember(existing) {
        mutableStateOf(existing?.outbound?.takeIf { it.isNotBlank() } ?: targets.firstOrNull() ?: "proxy")
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

            RulePreview(built = built, error = shownError, outbound = outbound)

            Text("出口", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(
                    onClick = { targetMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(outboundLabel(outbound), modifier = Modifier.weight(1f))
                    Text("▾")
                }
                DropdownMenu(expanded = targetMenu, onDismissRequest = { targetMenu = false }) {
                    targets.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(outboundLabel(target)) },
                            onClick = {
                                outbound = target
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

private fun outboundLabel(target: String): String = when (target) {
    "proxy" -> "proxy · 代理"
    "direct" -> "direct · 直连"
    "block" -> "block · 阻断"
    "must_direct" -> "must_direct · 不劫持 DNS 直连"
    else -> target
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
