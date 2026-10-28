package io.github.daepilot.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A curated accent set: three brand tones plus the wallpaper hue. Neutrals
 *  stay neutral for every choice; only interactive emphasis changes colour. */
enum class AccentChoice(
    val id: String,
    val label: String,
    val hue: Float,
) {
    FOREST("forest", "森林绿", 158f),
    OCEAN("ocean", "海蓝", 208f),
    INDIGO("indigo", "靛紫", 243f),
    SYSTEM("system", "跟随壁纸", 0f),
    ;

    val swatch: Color
        get() = if (isSystem) {
            Color.Transparent
        } else {
            Color.hsl(hue, ACCENT_SATURATION, LIGHT_PRIMARY_LIGHTNESS)
        }

    val isSystem: Boolean
        get() = this == SYSTEM

    companion object {
        fun fromId(id: String?): AccentChoice =
            entries.firstOrNull { it.id == id } ?: FOREST

        /** Pastel wallpapers have no reliable hue, so the theme falls back to forest. */
        fun wallpaperHue(context: Context, dark: Boolean): Float? {
            val primary = if (dark) {
                dynamicDarkColorScheme(context).primary
            } else {
                dynamicLightColorScheme(context).primary
            }
            if (colorSaturation(primary) < 0.08f) return null
            return colorHue(primary)
        }
    }
}

internal fun colorHue(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    if (delta < 1e-5f) return 0f
    val hue = when (maxC) {
        r -> (g - b) / delta + if (g < b) 6f else 0f
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return (hue * 60f) % 360f
}

internal fun colorSaturation(color: Color): Float {
    val maxC = max(color.red, max(color.green, color.blue))
    val minC = min(color.red, min(color.green, color.blue))
    val lightness = (maxC + minC) / 2f
    val delta = maxC - minC
    if (delta < 1e-5f) return 0f
    return delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-5f)
}

internal fun accentScheme(hue: Float, dark: Boolean): ColorScheme =
    if (dark) darkAccentScheme(hue) else lightAccentScheme(hue)

private const val ACCENT_SATURATION = 0.36f
private const val LIGHT_PRIMARY_LIGHTNESS = 0.34f
private const val DARK_PRIMARY_LIGHTNESS = 0.73f

private fun lightAccentScheme(h: Float): ColorScheme {
    val primary = Color.hsl(h, ACCENT_SATURATION, LIGHT_PRIMARY_LIGHTNESS)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = Color.hsl(h, 0.26f, 0.90f),
        onPrimaryContainer = Color.hsl(h, 0.36f, 0.17f),
        inversePrimary = Color.hsl(h, 0.34f, 0.72f),
        secondary = Color(0xFF5E6461),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE9EAE7),
        onSecondaryContainer = Color(0xFF1A1C1B),
        tertiary = Color(0xFF747A76),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFECEDEA),
        onTertiaryContainer = Color(0xFF1A1C1B),
        background = Color(0xFFF4F4F1),
        onBackground = Color(0xFF17191A),
        surface = Color(0xFFFBFBF9),
        onSurface = Color(0xFF17191A),
        surfaceVariant = Color(0xFFE9E9E6),
        onSurfaceVariant = Color(0xFF6B716D),
        surfaceTint = primary,
        inverseSurface = Color(0xFF2A2D2C),
        inverseOnSurface = Color(0xFFF2F3F0),
        error = ErrorLight,
        onError = Color.White,
        errorContainer = Color(0xFFF6E0DD),
        onErrorContainer = Color(0xFF4A0F0A),
        outline = Color(0xFFC9CBC7),
        outlineVariant = Color(0xFFE3E4E1),
        scrim = Color.Black,
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFE6E6E2),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFAFAF8),
        surfaceContainer = Color(0xFFF4F4F1),
        surfaceContainerHigh = Color(0xFFEEEFEC),
        surfaceContainerHighest = Color(0xFFE8E9E6),
    )
}

private fun darkAccentScheme(h: Float): ColorScheme {
    val primary = Color.hsl(h, 0.34f, DARK_PRIMARY_LIGHTNESS)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color.hsl(h, 0.30f, 0.12f),
        primaryContainer = Color.hsl(h, 0.24f, 0.22f),
        onPrimaryContainer = primary,
        inversePrimary = Color.hsl(h, ACCENT_SATURATION, LIGHT_PRIMARY_LIGHTNESS),
        secondary = Color(0xFFA6ACA8),
        onSecondary = Color(0xFF111312),
        secondaryContainer = Color(0xFF212522),
        onSecondaryContainer = Color(0xFFE2E5E1),
        tertiary = Color(0xFFB9C0BB),
        onTertiary = Color(0xFF111312),
        tertiaryContainer = Color(0xFF232825),
        onTertiaryContainer = Color(0xFFE3E6E2),
        background = Color(0xFF090A0A),
        onBackground = Color(0xFFECEEEA),
        surface = Color(0xFF0F1110),
        onSurface = Color(0xFFECEEEA),
        surfaceVariant = Color(0xFF1A1D1C),
        onSurfaceVariant = Color(0xFF9AA09C),
        surfaceTint = primary,
        inverseSurface = Color(0xFFECEEEA),
        inverseOnSurface = Color(0xFF111312),
        error = ErrorDark,
        onError = Color(0xFF3A0B08),
        errorContainer = Color(0xFF5A1D18),
        onErrorContainer = Color(0xFFF6DDD9),
        outline = Color(0xFF3B3F3D),
        outlineVariant = Color(0xFF242827),
        scrim = Color.Black,
        surfaceBright = Color(0xFF232624),
        surfaceDim = Color(0xFF080909),
        surfaceContainerLowest = Color(0xFF0B0C0C),
        surfaceContainerLow = Color(0xFF0E100F),
        surfaceContainer = Color(0xFF121413),
        surfaceContainerHigh = Color(0xFF171A18),
        surfaceContainerHighest = Color(0xFF1D211F),
    )
}

/** Neutral ink surfaces plus the accent as a faint light pool. The card layers
 *  are opaque enough to read as frosted panels; real blur is reserved for the
 *  floating dock, where it actually buys something. */
internal fun glassPalette(scheme: ColorScheme, dark: Boolean): DaeGlassPalette =
    if (dark) {
        DaeGlassPalette(
            isDark = true,
            baseTop = Color(0xFF0D0E0E),
            baseBottom = Color(0xFF080909),
            ambientCool = scheme.primary,
            ambientDeep = scheme.primary,
            glassTop = Color.White.copy(alpha = 0.075f),
            glassBottom = Color.White.copy(alpha = 0.040f),
            borderTop = Color.White.copy(alpha = 0.12f),
            borderBottom = Color.White.copy(alpha = 0.05f),
            shadow = Color.Black.copy(alpha = 0.45f),
            glow = scheme.primary.copy(alpha = 0.18f),
            dockTint = Color.Black.copy(alpha = 0.28f),
            fallbackTint = Color(0xFF141616).copy(alpha = 0.92f),
            success = SuccessDark,
            warning = WarningDark,
        )
    } else {
        DaeGlassPalette(
            isDark = false,
            baseTop = Color(0xFFF7F7F4),
            baseBottom = Color(0xFFEFEFEB),
            ambientCool = scheme.primary,
            ambientDeep = scheme.primary,
            glassTop = Color.White.copy(alpha = 0.86f),
            glassBottom = Color.White.copy(alpha = 0.76f),
            borderTop = Color.Black.copy(alpha = 0.05f),
            borderBottom = Color.Black.copy(alpha = 0.03f),
            shadow = Color.Black.copy(alpha = 0.07f),
            glow = scheme.primary.copy(alpha = 0.12f),
            dockTint = Color.White.copy(alpha = 0.55f),
            fallbackTint = Color.White.copy(alpha = 0.90f),
            success = SuccessLight,
            warning = WarningLight,
        )
    }
