package com.andsi.airlyrics.design.theme

import android.graphics.Bitmap
import com.andsi.airlyrics.core.model.ThemeAccent
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.Score

/** Material 3 color roles generated from one seed color (same algorithm as Android Monet). */
internal data class TonalScheme(
    val seed: Int,
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val surface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val error: Int
) {
    companion object {
        private val cache = object : LinkedHashMap<Long, TonalScheme>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TonalScheme>?): Boolean = size > 12
        }

        fun of(seed: Int, isDark: Boolean): TonalScheme = synchronized(cache) {
            val key = (seed.toLong() shl 1) or (if (isDark) 1L else 0L)
            cache.getOrPut(key) { build(seed, isDark) }
        }

        private fun build(seed: Int, isDark: Boolean): TonalScheme {
            val scheme = SchemeTonalSpot(Hct.fromInt(seed), isDark, 0.0)
            val colors = MaterialDynamicColors()
            return TonalScheme(
                seed = seed,
                isDark = isDark,
                primary = colors.primary().getArgb(scheme),
                onPrimary = colors.onPrimary().getArgb(scheme),
                primaryContainer = colors.primaryContainer().getArgb(scheme),
                onPrimaryContainer = colors.onPrimaryContainer().getArgb(scheme),
                secondary = colors.secondary().getArgb(scheme),
                secondaryContainer = colors.secondaryContainer().getArgb(scheme),
                onSecondaryContainer = colors.onSecondaryContainer().getArgb(scheme),
                tertiary = colors.tertiary().getArgb(scheme),
                tertiaryContainer = colors.tertiaryContainer().getArgb(scheme),
                onTertiaryContainer = colors.onTertiaryContainer().getArgb(scheme),
                surface = colors.surface().getArgb(scheme),
                surfaceContainerLowest = colors.surfaceContainerLowest().getArgb(scheme),
                surfaceContainerLow = colors.surfaceContainerLow().getArgb(scheme),
                surfaceContainer = colors.surfaceContainer().getArgb(scheme),
                surfaceContainerHigh = colors.surfaceContainerHigh().getArgb(scheme),
                surfaceContainerHighest = colors.surfaceContainerHighest().getArgb(scheme),
                onSurface = colors.onSurface().getArgb(scheme),
                onSurfaceVariant = colors.onSurfaceVariant().getArgb(scheme),
                outline = colors.outline().getArgb(scheme),
                outlineVariant = colors.outlineVariant().getArgb(scheme),
                inverseSurface = colors.inverseSurface().getArgb(scheme),
                inverseOnSurface = colors.inverseOnSurface().getArgb(scheme),
                error = colors.error().getArgb(scheme)
            )
        }

        /** Seed colors for the built-in tonal presets. */
        fun presetSeed(accent: ThemeAccent): Int? = when (accent) {
            ThemeAccent.TEAL -> 0xFF2A9D8F.toInt()
            ThemeAccent.SKY -> 0xFF3A9AD9.toInt()
            ThemeAccent.INDIGO -> 0xFF4F5BD5.toInt()
            ThemeAccent.GRAPE -> 0xFF8E44AD.toInt()
            ThemeAccent.ROSE -> 0xFFD6336C.toInt()
            ThemeAccent.CORAL -> 0xFFE76F51.toInt()
            ThemeAccent.AMBER -> 0xFFE09F3E.toInt()
            ThemeAccent.LIME -> 0xFF8AB33A.toInt()
            ThemeAccent.FOREST -> 0xFF2D7D46.toInt()
            ThemeAccent.SLATE -> 0xFF5A6B7D.toInt()
            ThemeAccent.COCOA -> 0xFF8D6E63.toInt()
            ThemeAccent.PINK -> 0xFFF18FA9.toInt()
            ThemeAccent.ORANGE -> 0xFFE9855A.toInt()
            ThemeAccent.GREEN -> 0xFF54A987.toInt()
            ThemeAccent.BLUE -> 0xFF5D94E8.toInt()
            ThemeAccent.PURPLE -> 0xFF9877D9.toInt()
            ThemeAccent.DYNAMIC, ThemeAccent.COVER -> null
        }

        /** Picks the most suitable theme seed from artwork, like Android does for wallpapers. */
        fun seedFromBitmap(source: Bitmap): Int? {
            // Hardware bitmaps cannot be read pixel by pixel.
            val bitmap = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
            val sample = if (bitmap.width * bitmap.height > 128 * 128) {
                Bitmap.createScaledBitmap(bitmap, 112, 112, true)
            } else bitmap
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            if (sample !== bitmap) sample.recycle()
            if (bitmap !== source) bitmap.recycle()
            if (pixels.isEmpty()) return null
            val quantized = QuantizerCelebi.quantize(pixels, 128)
            return Score.score(quantized, 1, DEFAULT_SEED, true).firstOrNull()
        }

        const val DEFAULT_SEED: Int = 0xFF4285F4.toInt()
    }
}
