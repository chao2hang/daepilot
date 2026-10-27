package io.github.daepilot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = Forest,
    onPrimary = PaperRaised,
    primaryContainer = SageWash,
    onPrimaryContainer = ForestInk,
    secondary = Stone,
    onSecondary = PaperRaised,
    secondaryContainer = StoneWash,
    onSecondaryContainer = Ink,
    tertiary = Brass,
    onTertiary = PaperRaised,
    tertiaryContainer = BrassWash,
    onTertiaryContainer = Color(0xFF2E2008),
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperSunk,
    onSurfaceVariant = InkMuted,
    outline = HairlineStrong,
    outlineVariant = Hairline,
    error = Brick,
    onError = PaperRaised,
    errorContainer = Color(0xFFF4DED8),
    onErrorContainer = Color(0xFF3E0E08),
)

private val DarkScheme = darkColorScheme(
    primary = Moss,
    onPrimary = MossInk,
    primaryContainer = Color(0xFF26372A),
    onPrimaryContainer = Moss,
    secondary = NightMuted,
    onSecondary = Night,
    secondaryContainer = Color(0xFF2F342C),
    onSecondaryContainer = NightInk,
    tertiary = NightBrass,
    onTertiary = Color(0xFF2E2008),
    tertiaryContainer = Color(0xFF463519),
    onTertiaryContainer = Color(0xFFF4DDB8),
    background = Night,
    onBackground = NightInk,
    surface = NightRaised,
    onSurface = NightInk,
    surfaceVariant = NightSunk,
    onSurfaceVariant = NightMuted,
    outline = NightHairlineStrong,
    outlineVariant = NightHairline,
    error = BrickNight,
    onError = Color(0xFF3E0E08),
    errorContainer = Color(0xFF5E1B13),
    onErrorContainer = Color(0xFFF4DED8),
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
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = DaePilotTypography,
        shapes = DaePilotShapes,
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
