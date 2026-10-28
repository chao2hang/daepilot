package io.github.daepilot.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    private val presets = AccentChoice.entries.filterNot { it.isSystem }

    @Test
    fun `light accents stay readable`() {
        presets.forEach { choice ->
            val scheme = accentScheme(choice.hue, dark = false)
            assertContrast(choice.id, "primary", scheme.primary, scheme.onPrimary, 4.5)
            assertContrast(
                choice.id,
                "primaryContainer",
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                4.5,
            )
            assertContrast(choice.id, "background", scheme.background, scheme.onBackground, 7.0)
            assertContrast(
                choice.id,
                "onSurfaceVariant",
                scheme.surface,
                scheme.onSurfaceVariant,
                4.5,
            )
            assertContrast("light", "success", SuccessLight, Color(0xFFFBFBF9), 4.5)
            assertContrast("light", "warning", WarningLight, Color(0xFFFBFBF9), 4.5)
        }
    }

    @Test
    fun `dark accents stay readable`() {
        presets.forEach { choice ->
            val scheme = accentScheme(choice.hue, dark = true)
            assertContrast(choice.id, "primary", scheme.primary, scheme.onPrimary, 4.5)
            assertContrast(
                choice.id,
                "primaryContainer",
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                4.5,
            )
            assertContrast(choice.id, "background", scheme.background, scheme.onBackground, 7.0)
            assertContrast(
                choice.id,
                "onSurfaceVariant",
                scheme.surface,
                scheme.onSurfaceVariant,
                4.5,
            )
            assertContrast("dark", "success", SuccessDark, Color(0xFF0F1110), 4.5)
            assertContrast("dark", "warning", WarningDark, Color(0xFF0F1110), 4.5)
        }
    }

    @Test
    fun `every preset builds a glass palette in both modes`() {
        presets.forEach { choice ->
            val light = accentScheme(choice.hue, dark = false)
            val dark = accentScheme(choice.hue, dark = true)
            assertFalse(glassPalette(light, dark = false).isDark)
            assertTrue(glassPalette(dark, dark = true).isDark)
        }
    }

    @Test
    fun `unknown accent falls back to forest`() {
        assertEquals(AccentChoice.FOREST, AccentChoice.fromId(null))
        assertEquals(AccentChoice.FOREST, AccentChoice.fromId("plasma"))
        assertEquals(AccentChoice.FOREST, AccentChoice.fromId(""))
    }

    @Test
    fun `wallpaper hue extraction matches known colours`() {
        assertEquals(158f, colorHue(Forest), 2f)
        assertEquals(208f, colorHue(Ocean), 2f)
        assertEquals(243f, colorHue(Indigo), 2f)
        assertTrue(colorSaturation(Forest) > 0.3f)
        assertTrue(colorSaturation(Color(0xFF808080)) < 0.08f)
    }

    private fun assertContrast(id: String, role: String, a: Color, b: Color, min: Double) {
        val actual = contrastRatio(a, b)
        assertTrue(
            "$id $role contrast ${"%.2f".format(actual)} < $min",
            actual >= min,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
