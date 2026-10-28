package io.github.daepilot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AmbientMode { Running, Setup, Idle }

/** Colours for the neutral glass: ink surfaces, a single accent light pool and
 *  the status tones. Kept separate from MaterialTheme so translucent layers can
 *  share one set of edges and shadows. */
@Immutable
data class DaeGlassPalette(
    val isDark: Boolean,
    val baseTop: Color,
    val baseBottom: Color,
    val ambientCool: Color,
    val ambientDeep: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val shadow: Color,
    val glow: Color,
    val dockTint: Color,
    val fallbackTint: Color,
    val success: Color,
    val warning: Color,
)

val LocalDaeGlassPalette = staticCompositionLocalOf {
    glassPalette(accentScheme(AccentChoice.FOREST.hue, dark = false), dark = false)
}

@Composable
fun DaePilotTheme(
    themeMode: String = "system",
    accentColor: String = AccentChoice.FOREST.id,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = AccentChoice.fromId(accentColor)
    val context = LocalContext.current
    // Wallpaper colours only exist on Android 12+. Take the hue, not the whole
    // palette: the background stays neutral so a peach wallpaper cannot turn
    // every surface beige.
    val systemHue = remember(context, dark, accent) {
        if (accent.isSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AccentChoice.wallpaperHue(context, dark)
        } else {
            null
        }
    }
    val hue = when {
        accent.isSystem -> systemHue ?: AccentChoice.FOREST.hue
        else -> accent.hue
    }
    val colorScheme = remember(hue, dark) { accentScheme(hue, dark) }
    val palette = remember(colorScheme, dark) { glassPalette(colorScheme, dark) }
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
        LocalDaeGlassPalette provides palette,
        // The app draws on a transparent Scaffold, so no Surface seeds the content
        // color. Without this, Text/Icon that skip an explicit color resolve to
        // Color.Unspecified and render black-on-black in dark mode.
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
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
