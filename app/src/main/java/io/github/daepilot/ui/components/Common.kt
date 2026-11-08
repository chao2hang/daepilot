package io.github.daepilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daepilot.core.model.TrafficInfo
import io.github.daepilot.core.model.ConnectionPhase
import io.github.daepilot.core.model.ConnectionState
import io.github.daepilot.ui.Format
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

val ScreenEdge = 20.dp
val ScreenBottom = 132.dp

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(modifier = Modifier.padding(end = 4.dp)) { leading() }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            actions()
        }
    }
}

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
        spacing = 12.dp,
    ) {
        if (title != null || action != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                action?.invoke()
            }
        }
        content()
    }
}

@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassGroup(modifier = modifier.fillMaxWidth(), content = content)
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.75.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
    )
}

@Composable
fun TrafficBar(traffic: TrafficInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "已用 ${Format.bytes(traffic.usedBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (traffic.totalBytes > 0) "共 ${Format.bytes(traffic.totalBytes)}" else "流量未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val palette = LocalDaeGlassPalette.current
        val tone = when {
            traffic.usageFraction > 0.9f -> MaterialTheme.colorScheme.error
            traffic.usageFraction > 0.7f -> palette.warning
            else -> palette.success
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(traffic.usageFraction.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(tone),
            )
        }
        if (traffic.expireAtEpochSeconds > 0) {
            Text(
                "到期 ${Format.expiry(traffic.expireAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(
            shape = CircleShape,
            contentPadding = PaddingValues(16.dp),
            spacing = 0.dp,
            elevation = 6.dp,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (primaryLabel != null && onPrimary != null) {
            TextButton(onClick = onPrimary, modifier = Modifier.padding(top = 6.dp)) {
                Text(primaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Always-visible outbound strip: exit IP when connected, the live switch phase
 * while a selection is being applied, and the failure reason otherwise.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    running: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!running) return
    val palette = LocalDaeGlassPalette.current
    val busy = state.phase == ConnectionPhase.APPLYING ||
        state.phase == ConnectionPhase.VERIFYING
    val cnExit = state.phase == ConnectionPhase.CONNECTED && state.exit?.country == "CN"
    val tone = when {
        state.phase == ConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
        cnExit -> palette.warning
        state.phase == ConnectionPhase.CONNECTED -> palette.success
        busy -> palette.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val connected = state.phase == ConnectionPhase.CONNECTED
    val exit = state.exit
    val place = listOf(exit?.country.orEmpty(), exit?.colo.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val meta = buildList {
        if (place.isNotBlank()) add(place)
        state.latencyMs?.takeIf { it > 0 }?.let { add("$it ms") }
        if (state.checkedAt > 0) add(Format.relativeTime(state.checkedAt))
        if (cnExit) add("国内出口")
    }.joinToString(" · ")
    val headline = when (state.phase) {
        ConnectionPhase.CONNECTED -> "出口"
        ConnectionPhase.APPLYING -> "正在切换"
        ConnectionPhase.VERIFYING -> "正在验证出口"
        ConnectionPhase.FAILED -> "连接异常"
        ConnectionPhase.STOPPED -> "出口 IP 待刷新"
    }
    val line = when (state.phase) {
        ConnectionPhase.CONNECTED -> meta
        ConnectionPhase.APPLYING -> "正在应用 ${state.target} 的配置"
        ConnectionPhase.VERIFYING -> "配置已应用，正在确认出口"
        ConnectionPhase.FAILED ->
            listOf(state.target, state.detail.ifBlank { "出口不可达" })
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        ConnectionPhase.STOPPED -> "点击刷新读取当前出口"
    }
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        tone.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                    ),
                ),
            )
            .border(0.75.dp, tone.copy(alpha = 0.30f), shape)
            .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    headline,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        tone
                    },
                )
                if (connected) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        exit?.ip ?: "出口未知",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = tone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(13.dp),
                strokeWidth = 1.8.dp,
                color = tone,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新出口 IP",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = confirmEnabled,
            ) {
                Text(
                    confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
