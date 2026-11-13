package io.github.daepilot.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.daepilot.data.AppCatalog
import io.github.daepilot.data.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BypassAppSheet(
    current: List<String>,
    pnameUnavailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var selected by remember { mutableStateOf(current.toSet()) }
    var query by remember { mutableStateOf("") }
    var manualInput by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = AppCatalog.launchableApps(context)
    }

    val catalog = apps.orEmpty()
    val catalogPackages = remember(catalog) { catalog.map { it.packageName }.toSet() }
    val filtered = remember(catalog, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            catalog
        } else {
            catalog.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
    }
    val manualSelected = if (apps == null) {
        emptyList()
    } else {
        selected.filter { it !in catalogPackages }.sorted()
    }
    val addManual = {
        val value = manualInput.trim()
        if (value.isEmpty()) {
            manualError = "请输入包名"
        } else if (!AppCatalog.isValidPackageName(value)) {
            manualError = "包名格式不正确，例如 com.example.app"
        } else {
            selected = selected + value
            manualInput = ""
            manualError = null
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
                .fillMaxHeight(0.92f)
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("分应用绕过", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "选中的应用直连，不经过 dae",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            if (pnameUnavailable) {
                Text(
                    "本机 pname 不可用；保存后会通过 UID 标记让这些应用直连（需要当前模块版本）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Android 进程名最长 15 个字符，dae 会按尾部匹配，无需手动截断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索应用或包名",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (catalog.isEmpty()) {
                        "已选 ${selected.size} 个"
                    } else {
                        "已选 ${selected.size} / 共 ${catalog.size} 个"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        selected = selected + filtered.map { it.packageName }
                    },
                    enabled = filtered.isNotEmpty(),
                ) { Text(if (query.isBlank()) "全选" else "全选结果") }
                TextButton(
                    onClick = { selected = emptySet() },
                    enabled = selected.isNotEmpty(),
                ) { Text("清空") }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    apps == null -> CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                    )
                    filtered.isEmpty() -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (query.isBlank()) "未读取到可启动的应用" else "没有匹配的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (catalog.isEmpty()) {
                            Text(
                                "可在下方手动添加包名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppPickRow(
                                app = app,
                                selected = app.packageName in selected,
                                onToggle = {
                                    selected = if (app.packageName in selected) {
                                        selected - app.packageName
                                    } else {
                                        selected + app.packageName
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (manualSelected.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    manualSelected.forEach { packageName ->
                        InputChip(
                            selected = true,
                            onClick = { selected = selected - packageName },
                            label = { Text(packageName, maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "移除",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = {
                        manualInput = it
                        manualError = null
                    },
                    label = { Text("手动添加包名") },
                    placeholder = { Text("com.example.app") },
                    singleLine = true,
                    isError = manualError != null,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                )
                IconButton(onClick = addManual) {
                    Icon(Icons.Outlined.Add, contentDescription = "添加包名")
                }
            }
            if (manualError != null) {
                Text(
                    manualError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("取消") }
                Button(
                    onClick = { onSave(selected.sorted()) },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun AppPickRow(
    app: InstalledApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        AppIcon(packageName = app.packageName, modifier = Modifier.size(38.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(11.dp)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                packageName.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
