package io.github.daepilot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.daepilot.ui.theme.AmbientMode
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

/** Large, slowly shifting light pools behind the translucent layers. The two
 *  active colours follow the daemon state so the glass feels environment-aware. */
@Composable
fun AmbientBackground(
    mode: AmbientMode,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    val cool by animateColorAsState(palette.ambientCool, tween(900), label = "ambientCool")
    val deep by animateColorAsState(palette.ambientDeep, tween(900), label = "ambientDeep")
    val intensity = when (mode) {
        AmbientMode.Running -> 1f
        AmbientMode.Setup -> 0.8f
        AmbientMode.Idle -> 0.45f
    }
    val coolAlpha = (if (palette.isDark) 0.17f else 0.075f) * intensity
    val deepAlpha = (if (palette.isDark) 0.12f else 0.055f) * intensity

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(palette.baseTop, palette.baseBottom)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cool.copy(alpha = coolAlpha), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.02f),
                radius = size.width * 1.05f,
            ),
            radius = size.width * 1.05f,
            center = Offset(size.width * 0.88f, size.height * 0.02f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(deep.copy(alpha = deepAlpha), Color.Transparent),
                center = Offset(size.width * 0.05f, size.height * 0.55f),
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.05f, size.height * 0.55f),
        )
        drawRect(
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (palette.isDark) 0.035f else 0.10f),
                    Color.Transparent,
                ),
                endY = size.height * 0.30f,
            ),
        )
    }
}

/** The shared soft-glass surface: a translucent vertical gradient, a light-catching
 *  edge and an ambient shadow. Pass [tone] to tint a selected row. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    spacing: Dp = 12.dp,
    elevation: Dp = 10.dp,
    tone: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDaeGlassPalette.current
    val base = modifier
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = palette.shadow,
            spotColor = palette.shadow,
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(palette.glassTop, palette.glassBottom)))
    val tinted = if (tone != null) {
        base.background(tone.copy(alpha = 0.08f))
    } else {
        base
    }
    Column(
        modifier = tinted
            .border(0.75.dp, Brush.verticalGradient(listOf(palette.borderTop, palette.borderBottom)), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        spacing = 0.dp,
        content = content,
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(palette.glassTop, palette.glassBottom)))
            .border(0.75.dp, Brush.verticalGradient(listOf(palette.borderTop, palette.borderBottom)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
fun GlassAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container = if (LocalDaeGlassPalette.current.isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(16.dp),
        color = container.copy(alpha = 0.90f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            0.75.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        spacing = 0.dp,
        elevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
fun DaeChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        tween(180),
        label = "chipBackground",
    )
    val borderColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        },
        tween(180),
        label = "chipBorder",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = CircleShape,
        color = background,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
