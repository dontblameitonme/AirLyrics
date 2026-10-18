package com.andsi.airlyrics.ui.pages.nowplaying

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.google.android.material.button.MaterialButton

/** Material 3 context for the lyrics page; colors still follow the app's runtime palette. */
internal fun MainUiHost.nowPlayingThemedContext(): Context = ContextThemeWrapper(this, R.style.Theme_AirLyrics_NowPlaying)

internal fun Context.materialIconButton(
    host: MainUiHost,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tonal: Boolean = false
): MaterialButton {
    val styleAttr = if (tonal) {
        com.google.android.material.R.attr.materialIconButtonFilledTonalStyle
    } else {
        com.google.android.material.R.attr.materialIconButtonStyle
    }
    return MaterialButton(this, null, styleAttr).apply {
        setIconResource(icon)
        contentDescription = host.getString(label)
        tooltipText = contentDescription
        iconTint = ColorStateList.valueOf(host.colorTextStrong)
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 48))
        if (tonal) backgroundTintList = ColorStateList.valueOf(host.colorAccentSoft)
    }
}

/** Material dialogs read colorPrimary from the static theme, so retint the actions. */
internal fun AlertDialog.tintButtons(host: MainUiHost) {
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
        getButton(which)?.setTextColor(host.colorAccent)
    }
}

/**
 * Light Markdown rendering for AI replies: headings, bold, bullet lists. Anything else is
 * kept as plain text, which keeps partially streamed output readable.
 */
internal fun renderLightMarkdown(source: String): CharSequence {
    val out = SpannableStringBuilder()
    source.lines().forEachIndexed { index, rawLine ->
        if (index > 0) out.append('\n')
        var line = rawLine
        val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$").find(line)
        val lineStart = out.length
        if (heading != null) {
            line = heading.groupValues[2]
        } else {
            Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)?.let { line = it.groupValues[1] + "• " + it.groupValues[2] }
        }
        appendInlineBold(out, line)
        if (heading != null && out.length > lineStart) {
            out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(if (heading.groupValues[1].length <= 2) 1.15f else 1.05f), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return out
}

private fun appendInlineBold(out: SpannableStringBuilder, line: String) {
    var cursor = 0
    val bold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    for (match in bold.findAll(line)) {
        out.append(line, cursor, match.range.first)
        val start = out.length
        out.append(match.groupValues[1].ifEmpty { match.groupValues[2] })
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cursor = match.range.last + 1
    }
    out.append(line, cursor, line.length)
}

internal fun formatCount(count: Long): String =
    if (count < 10_000L) count.toString()
    else android.icu.text.CompactDecimalFormat
        .getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
        .format(count)
