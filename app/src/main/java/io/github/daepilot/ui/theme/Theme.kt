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
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenContainer,
    onPrimaryContainer = GreenOnContainer,
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = Color(0xFF17211C),
    tertiary = Sand,
    onTertiary = Color.White,
    tertiaryContainer = SandContainer,
    onTertiaryContainer = Color(0xFF2C1D02),
    background = Paper,
    onBackground = Ink,
    surface = CardLight,
    onSurface = Ink,
    surfaceVariant = FillLight,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFF89938D),
    outlineVariant = LineLight,
    error = Red,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF00382C),
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = GreenOnContainerDark,
    secondary = SlateDark,
    onSecondary = Color(0xFF22322B),
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = Color(0xFFD4E5DC),
    tertiary = SandDark,
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = SandContainerDark,
    onTertiaryContainer = Color(0xFFFFE2AD),
    background = Night,
    onBackground = Color(0xFFE2E6E2),
    surface = CardDark,
    onSurface = Color(0xFFE2E6E2),
    surfaceVariant = FillDark,
    onSurfaceVariant = NightMuted,
    outline = Color(0xFF707B75),
    outlineVariant = LineDark,
    error = RedDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
