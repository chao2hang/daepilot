package io.github.daepilot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AmbientMode { Running, Setup, Idle }

/** Colours for the soft-glass material. Kept separate from MaterialTheme so
 *  translucent layers can share the same light-catching edges and shadow. */
@Immutable
data class DaeGlassPalette(
    val isDark: Boolean,
    val baseTop: Color,
    val baseBottom: Color,
    val ambientCool: Color,
    val ambientWarm: Color,
    val ambientDeep: Color,
    val ambientNeutral: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val shadow: Color,
    val glow: Color,
    val dockTint: Color,
    val fallbackTint: Color,
)

private val LightGlassPalette = DaeGlassPalette(
    isDark = false,
    baseTop = Color(0xFFF4F7F2),
    baseBottom = Color(0xFFE4ECE6),
    ambientCool = AmbientMint,
    ambientWarm = AmbientSand,
    ambientDeep = AmbientTeal,
    ambientNeutral = AmbientStone,
    glassTop = Color.White.copy(alpha = 0.74f),
    glassBottom = Color.White.copy(alpha = 0.48f),
    borderTop = Color.White.copy(alpha = 0.96f),
    borderBottom = Color.White.copy(alpha = 0.20f),
    shadow = ForestDeep.copy(alpha = 0.12f),
    glow = Forest.copy(alpha = 0.24f),
    dockTint = Color.White.copy(alpha = 0.46f),
    fallbackTint = Color.White.copy(alpha = 0.78f),
)

private val DarkGlassPalette = DaeGlassPalette(
    isDark = true,
    baseTop = Color(0xFF0D1310),
    baseBottom = Color(0xFF070B09),
    ambientCool = NightAmbientMint,
    ambientWarm = NightAmbientSand,
    ambientDeep = NightAmbientTeal,
    ambientNeutral = NightAmbientStone,
    glassTop = Color(0xFF1B251F).copy(alpha = 0.78f),
    glassBottom = Color(0xFF111813).copy(alpha = 0.62f),
    borderTop = Color.White.copy(alpha = 0.24f),
    borderBottom = Color.White.copy(alpha = 0.05f),
    shadow = Color.Black.copy(alpha = 0.50f),
    glow = Moss.copy(alpha = 0.30f),
    dockTint = Night.copy(alpha = 0.40f),
    fallbackTint = NightRaised.copy(alpha = 0.88f),
)

val LocalDaeGlassPalette = staticCompositionLocalOf { LightGlassPalette }

private val LightScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EADF),
    onPrimaryContainer = ForestInk,
    inversePrimary = Moss,
    secondary = Mist,
    onSecondary = Color.White,
    secondaryContainer = MistWash,
    onSecondaryContainer = Ink,
    tertiary = Brass,
    onTertiary = Color.White,
    tertiaryContainer = BrassWash,
    onTertiaryContainer = Color(0xFF3A2A0B),
    background = Porcelain,
    onBackground = Ink,
    surface = Color(0xFFF8FBF7),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE4EBE5),
    onSurfaceVariant = InkMuted,
    surfaceTint = Forest,
    inverseSurface = ForestInk,
    inverseOnSurface = Color(0xFFF1F5F1),
    error = Brick,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = HairlineStrong,
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = Color(0xFFFBFDFA),
    surfaceDim = Color(0xFFDDE5DE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F8F3),
    surfaceContainer = Color(0xFFEFF4EE),
    surfaceContainerHigh = Color(0xFFE9F0E9),
    surfaceContainerHighest = Color(0xFFE3EBE4),
)

private val DarkScheme = darkColorScheme(
    primary = Moss,
    onPrimary = MossInk,
    primaryContainer = Color(0xFF24402F),
    onPrimaryContainer = Moss,
    inversePrimary = Forest,
    secondary = NightMuted,
    onSecondary = Night,
    secondaryContainer = Color(0xFF243029),
    onSecondaryContainer = NightInk,
    tertiary = Color(0xFFE8C486),
    onTertiary = Color(0xFF2E2008),
    tertiaryContainer = Color(0xFF463519),
    onTertiaryContainer = Color(0xFFF4DDB8),
    background = Night,
    onBackground = NightInk,
    surface = NightRaised,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF1B2620),
    onSurfaceVariant = NightMuted,
    surfaceTint = Moss,
    inverseSurface = NightInk,
    inverseOnSurface = Night,
    error = BrickNight,
    onError = Color(0xFF3E0E08),
    errorContainer = Color(0xFF5E1B13),
    onErrorContainer = Color(0xFFF4DED8),
    outline = NightHairlineStrong,
    outlineVariant = NightHairline,
    scrim = Color.Black,
    surfaceBright = Color(0xFF28312B),
    surfaceDim = Color(0xFF0A0F0C),
    surfaceContainerLowest = Color(0xFF0C120E),
    surfaceContainerLow = Color(0xFF101712),
    surfaceContainer = Color(0xFF141C16),
    surfaceContainerHigh = Color(0xFF19221B),
    surfaceContainerHighest = Color(0xFF1E2821),
)

@Composable
fun DaePilotTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(
        LocalDaeGlassPalette provides if (dark) DarkGlassPalette else LightGlassPalette,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = DaePilotTypography,
            shapes = DaePilotShapes,
            content = content,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
