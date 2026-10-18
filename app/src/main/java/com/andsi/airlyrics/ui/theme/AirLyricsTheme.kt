package com.andsi.airlyrics.ui.theme

import android.graphics.Color
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.theme.ThemeAccentPalettes
import com.andsi.airlyrics.design.theme.TonalScheme

internal object AirLyricsTheme {
    private val lightPalette = AirLyricsPalette(
        background = Color.rgb(255, 249, 243),
        surface = Color.rgb(255, 244, 236),
        surfaceLight = Color.rgb(255, 250, 246),
        card = Color.rgb(255, 255, 255),
        bubble = Color.argb(246, 255, 252, 248),
        stroke = Color.rgb(245, 221, 215),
        accent = Color.rgb(241, 143, 169),
        onAccent = Color.rgb(75, 42, 54),
        accentLight = Color.rgb(255, 184, 202),
        accentSoft = Color.rgb(159, 214, 203),
        accentPink = Color.rgb(255, 177, 197),
        accentMint = Color.rgb(150, 211, 203),
        danger = Color.rgb(198, 40, 40),
        textStrong = Color.rgb(91, 67, 76),
        text = Color.rgb(122, 94, 105),
        textMuted = Color.rgb(166, 132, 142)
    )

    private val darkPalette = AirLyricsPalette(
        background = Color.rgb(27, 23, 30),
        surface = Color.rgb(36, 30, 39),
        surfaceLight = Color.rgb(49, 40, 53),
        card = Color.rgb(43, 35, 47),
        bubble = Color.argb(248, 43, 35, 47),
        stroke = Color.rgb(75, 58, 70),
        accent = Color.rgb(246, 138, 171),
        onAccent = Color.rgb(64, 36, 47),
        accentLight = Color.rgb(255, 179, 202),
        accentSoft = Color.rgb(111, 191, 184),
        accentPink = Color.rgb(236, 126, 164),
        accentMint = Color.rgb(105, 190, 182),
        danger = Color.rgb(255, 107, 107),
        textStrong = Color.rgb(247, 229, 237),
        text = Color.rgb(224, 199, 211),
        textMuted = Color.rgb(178, 148, 164)
    )

    /**
     * Legacy accents keep the original warm palette; tonal accents (presets, Monet, cover) map the
     * whole UI onto a Material 3 tonal scheme generated from [seed] (or the preset seed).
     */
    fun palette(isDark: Boolean, accent: ThemeAccent, seed: Int? = null): AirLyricsPalette {
        if (accent.isTonal) {
            val resolvedSeed = seed ?: TonalScheme.presetSeed(accent)
            if (resolvedSeed != null) return tonalPalette(TonalScheme.of(resolvedSeed, isDark))
        }
        val legacyAccent = if (accent.isTonal) ThemeAccent.DEFAULT else accent
        val base = if (isDark) darkPalette else lightPalette
        val colors = ThemeAccentPalettes.resolve(legacyAccent, isDark)
        return base.copy(
            accent = colors.accent,
            onAccent = colors.onAccent,
            accentLight = colors.accentLight
        )
    }

    private val tonalCache = HashMap<Long, AirLyricsPalette>()

    private fun tonalPalette(scheme: TonalScheme): AirLyricsPalette = synchronized(tonalCache) {
        val key = (scheme.seed.toLong() shl 1) or (if (scheme.isDark) 1L else 0L)
        tonalCache.getOrPut(key) {
            val card = if (scheme.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
            AirLyricsPalette(
                background = scheme.surface,
                surface = scheme.surfaceContainer,
                surfaceLight = scheme.surfaceContainerLow,
                card = card,
                bubble = (card and 0x00FFFFFF) or ((if (scheme.isDark) 248 else 246) shl 24),
                stroke = scheme.outlineVariant,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                accentLight = scheme.primaryContainer,
                // Strokes and status tints; keeps contrast with onAccent like the legacy palette.
                accentSoft = scheme.secondary,
                accentPink = scheme.tertiary,
                accentMint = scheme.secondaryContainer,
                danger = scheme.error,
                textStrong = scheme.onSurface,
                text = scheme.onSurfaceVariant,
                textMuted = scheme.outline
            )
        }
    }
}
