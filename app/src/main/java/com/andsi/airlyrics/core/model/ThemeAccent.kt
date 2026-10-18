package com.andsi.airlyrics.core.model

/** Stable app-wide accent choices. Persist [preferenceValue], never enum names. */
enum class ThemeAccent(val preferenceValue: String) {
    PINK("pink"),
    ORANGE("orange"),
    GREEN("green"),
    BLUE("blue"),
    PURPLE("purple"),

    /** Android 12+ wallpaper (Monet) colors. */
    DYNAMIC("dynamic"),

    /** Colors extracted from the current song's artwork. */
    COVER("cover"),
    TEAL("teal"),
    SKY("sky"),
    INDIGO("indigo"),
    GRAPE("grape"),
    ROSE("rose"),
    CORAL("coral"),
    AMBER("amber"),
    LIME("lime"),
    FOREST("forest"),
    SLATE("slate"),
    COCOA("cocoa");

    /** Tonal accents recolor the whole UI from a Material 3 scheme instead of the legacy palette. */
    val isTonal: Boolean
        get() = ordinal >= DYNAMIC.ordinal

    companion object {
        val DEFAULT = PINK

        fun fromPreferenceValue(value: String?): ThemeAccent {
            return entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
        }
    }
}
