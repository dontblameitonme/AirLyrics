package com.andsi.airlyrics.ui.theme

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirLyricsThemeTest {
    @Test
    fun iconForegroundUsesEightyPercentOnAccentAlpha() {
        val onAccent = AirLyricsTheme.palette(isDark = false, ThemeAccent.PINK).onAccent
        val iconColor = iconColorOnAccent(onAccent)

        assertEquals(204, Color.alpha(iconColor))
        assertEquals(Color.red(onAccent), Color.red(iconColor))
        assertEquals(Color.green(onAccent), Color.green(iconColor))
        assertEquals(Color.blue(onAccent), Color.blue(iconColor))
    }

    @Test
    fun everyAccentHasDistinctLightAndDarkPrimaryColors() {
        // Wallpaper and album-art accents need a runtime seed; without one they fall back to pink.
        val fixed = ThemeAccent.entries.filter { it != ThemeAccent.DYNAMIC && it != ThemeAccent.COVER }
        val lightColors = fixed.map { AirLyricsTheme.palette(isDark = false, it).accent }
        val darkColors = fixed.map { AirLyricsTheme.palette(isDark = true, it).accent }

        assertEquals(fixed.size, lightColors.distinct().size)
        assertEquals(fixed.size, darkColors.distinct().size)
        ThemeAccent.entries.forEach { accent ->
            assertNotEquals(
                AirLyricsTheme.palette(isDark = false, accent).accent,
                AirLyricsTheme.palette(isDark = true, accent).accent
            )
        }
    }

    @Test
    fun changingAccentOnlyChangesPrimaryAccentRoles() {
        val pink = AirLyricsTheme.palette(isDark = false, ThemeAccent.PINK)
        val blue = AirLyricsTheme.palette(isDark = false, ThemeAccent.BLUE)

        assertNotEquals(pink.accent, blue.accent)
        assertNotEquals(pink.accentLight, blue.accentLight)
        assertNotEquals(pink.onAccent, blue.onAccent)
        assertEquals(pink.background, blue.background)
        assertEquals(pink.accentSoft, blue.accentSoft)
        assertEquals(pink.accentPink, blue.accentPink)
        assertEquals(pink.accentMint, blue.accentMint)
    }

    @Test
    fun accentForegroundsMeetNormalTextContrast() {
        listOf(false, true).forEach { isDark ->
            ThemeAccent.entries.forEach { accent ->
                val palette = AirLyricsTheme.palette(isDark, accent)
                assertTrue(
                    "$accent foreground contrast was below 4.5 in isDark=$isDark",
                    ColorUtils.calculateContrast(palette.onAccent, palette.accent) >= 4.5
                )
            }
        }
    }

    @Test
    fun accentForegroundsAlsoContrastWithSoftAccentSurfaces() {
        listOf(false, true).forEach { isDark ->
            ThemeAccent.entries.forEach { accent ->
                val palette = AirLyricsTheme.palette(isDark, accent)
                assertTrue(
                    "$accent soft foreground contrast was below 4.5 in isDark=$isDark",
                    ColorUtils.calculateContrast(palette.onAccent, palette.accentSoft) >= 4.5
                )
            }
        }
    }

    @Test
    fun tonalAccentsMapTheWholePaletteFromTheSeed() {
        listOf(false, true).forEach { isDark ->
            val seeded = AirLyricsTheme.palette(isDark, ThemeAccent.COVER, seed = 0xFF1E88E5.toInt())
            val other = AirLyricsTheme.palette(isDark, ThemeAccent.COVER, seed = 0xFFE53935.toInt())
            assertNotEquals(seeded.accent, other.accent)
            assertNotEquals(seeded.background, other.background)
            assertTrue(ColorUtils.calculateContrast(seeded.textStrong, seeded.background) >= 7.0)
            assertTrue(ColorUtils.calculateContrast(seeded.text, seeded.card) >= 4.5)
        }
        assertEquals(
            AirLyricsTheme.palette(false, ThemeAccent.PINK),
            AirLyricsTheme.palette(false, ThemeAccent.DYNAMIC)
        )
    }
}
