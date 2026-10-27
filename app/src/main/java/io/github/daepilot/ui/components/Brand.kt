package io.github.daepilot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** The DaePilot mark: a swallow-tail wing, matching the launcher icon. */
@Composable
fun WingMark(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.26f)
            lineTo(w * 0.48f, h * 0.26f)
            lineTo(w * 0.84f, h * 0.50f)
            lineTo(w * 0.48f, h * 0.74f)
            lineTo(w * 0.18f, h * 0.74f)
            lineTo(w * 0.56f, h * 0.50f)
            close()
        }
        drawPath(path, color)
    }
}
