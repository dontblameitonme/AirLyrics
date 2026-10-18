package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.core.prefs.prefs

/** Stores app-wide theme preferences. */
object ThemeSettingsStore {
    private const val PREF_NAME = "app_theme"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_COVER_SEED = "cover_seed"

    private fun store(context: Context) = prefs(context, PREF_NAME)

    /** Returns null until the user explicitly chooses light or dark mode. */
    fun getDarkModeOverride(context: Context): Boolean? {
        val preferences = store(context)
        return if (preferences.contains(KEY_DARK_MODE)) {
            preferences.getBoolean(KEY_DARK_MODE, false)
        } else {
            null
        }
    }

    fun isDark(context: Context): Boolean {
        return getDarkModeOverride(context) ?: isSystemDark(context)
    }

    private fun isSystemDark(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun setDark(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_DARK_MODE, enabled)
    }

    /** Clears the explicit light/dark choice so the app follows the system again. */
    fun setFollowSystem(context: Context) {
        store(context).setString(KEY_DARK_MODE, null)
    }

    fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun getCoverSeed(context: Context): Int? {
        val preferences = store(context)
        return if (preferences.contains(KEY_COVER_SEED)) preferences.getInt(KEY_COVER_SEED, 0) else null
    }

    /** Returns true when the stored cover seed changed. */
    fun setCoverSeed(context: Context, seed: Int): Boolean {
        if (getCoverSeed(context) == seed) return false
        store(context).setInt(KEY_COVER_SEED, seed)
        return true
    }

    /** Seed color for accents that are derived at runtime (system Monet or album artwork). */
    fun resolveSeed(context: Context, accent: ThemeAccent = getAccent(context)): Int? {
        return when (accent) {
            ThemeAccent.DYNAMIC -> if (isDynamicColorAvailable()) {
                runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrNull()
            } else null
            ThemeAccent.COVER -> getCoverSeed(context)
            else -> null
        }
    }

    fun getAccent(context: Context): ThemeAccent {
        return ThemeAccent.fromPreferenceValue(store(context).getString(KEY_ACCENT))
    }

    fun setAccent(context: Context, accent: ThemeAccent) {
        store(context).setString(KEY_ACCENT, accent.preferenceValue)
    }
}
