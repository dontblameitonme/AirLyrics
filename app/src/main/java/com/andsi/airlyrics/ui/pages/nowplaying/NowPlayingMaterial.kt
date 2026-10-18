package com.andsi.airlyrics.ui.pages.nowplaying

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.theme.TonalScheme
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.google.android.material.materialswitch.MaterialSwitch

/** Material 3 context for the lyrics page; colors still follow the app's runtime palette. */
internal fun MainUiHost.nowPlayingThemedContext(): Context = ContextThemeWrapper(this, R.style.Theme_AirLyrics_NowPlaying)

/**
 * Tonal roles used by the lyrics page and every sheet it opens, generated from the same seed
 * as the app palette so menus, sheets and controls never mix unrelated hues.
 */
internal fun MainUiHost.nowPlayingScheme(): TonalScheme {
    val accent = themeAccent()
    val seed = themeSeed() ?: TonalScheme.presetSeed(accent) ?: colorAccent
    return TonalScheme.of(seed, isDarkTheme())
}

internal fun Int.withAlpha(alpha: Int): Int = ColorUtils.setAlphaComponent(this, alpha)

internal fun roundedRect(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Ripple over [content] (or borderless when both are null), tinted for the scheme. */
internal fun rippleOf(rippleColor: Int, content: Drawable?, mask: Drawable? = content): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor.withAlpha(40)), content, mask)

/** Borderless icon button (40dp touch target unless [boxDp] says otherwise). */
internal fun Context.iconButton(
    host: MainUiHost,
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tint: Int,
    iconDp: Int = 24,
    boxDp: Int = 44
): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    contentDescription = host.getString(label)
    tooltipText = contentDescription
    val pad = host.dp((boxDp - iconDp) / 2)
    setPadding(pad, pad, pad, pad)
    layoutParams = ViewGroup.LayoutParams(host.dp(boxDp), host.dp(boxDp))
    background = rippleOf(tint, null, null)
    isClickable = true
    isFocusable = true
}

/** Text-only pill button; [filled] uses primary, otherwise a quiet text button. */
internal fun Context.pillButton(host: MainUiHost, scheme: TonalScheme, text: CharSequence, filled: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (filled) scheme.onPrimary else scheme.primary)
        minHeight = host.dp(40)
        setPadding(host.dp(18), host.dp(8), host.dp(18), host.dp(8))
        val radius = host.dp(20).toFloat()
        val shape = roundedRect(if (filled) scheme.primary else 0, radius)
        background = rippleOf(if (filled) scheme.onPrimary else scheme.primary, shape, roundedRect(0xFFFFFFFF.toInt(), radius))
        isClickable = true
        isFocusable = true
    }

internal fun Context.divider(host: MainUiHost, scheme: TonalScheme, startInsetDp: Int = 0, verticalDp: Int = 0): View =
    View(this).apply {
        setBackgroundColor(scheme.outlineVariant.withAlpha(if (scheme.isDark) 140 else 200))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, host.dp(1).coerceAtLeast(1)).apply {
            marginStart = host.dp(startInsetDp)
            topMargin = host.dp(verticalDp)
            bottomMargin = host.dp(verticalDp)
        }
    }

/** Material switch recolored with the page scheme instead of the static theme. */
internal fun Context.schemeSwitch(scheme: TonalScheme): MaterialSwitch = MaterialSwitch(this).apply {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(scheme.onPrimary, scheme.outline))
    trackTintList = ColorStateList(states, intArrayOf(scheme.primary, scheme.surfaceContainerHighest))
    trackDecorationTintList = ColorStateList(states, intArrayOf(scheme.primary, scheme.outline))
    isClickable = false
    isFocusable = false
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
