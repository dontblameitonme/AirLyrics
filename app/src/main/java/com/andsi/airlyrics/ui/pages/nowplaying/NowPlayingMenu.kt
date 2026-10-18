package com.andsi.airlyrics.ui.pages.nowplaying

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.design.theme.TonalScheme
import com.andsi.airlyrics.ui.model.MainUiHost

/** One overflow-menu row; [checked] non-null shows a switch. */
internal class NowPlayingMenuItem(
    val label: String,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Text-only overflow menu drawn with the page scheme (the platform popup menu would use the
 * static theme colors). Groups are separated by thin dividers.
 */
internal fun showNowPlayingMenu(
    host: MainUiHost,
    ui: Context,
    scheme: TonalScheme,
    anchor: View,
    groups: List<List<NowPlayingMenuItem>>
) {
    val column = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, host.dp(6), 0, host.dp(6))
    }
    val popup = PopupWindow(
        ScrollView(ui).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
            background = roundedRect(
                if (scheme.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
                host.dp(16).toFloat()
            )
            elevation = host.dp(8).toFloat()
            clipToOutline = true
        },
        host.dp(MENU_WIDTH_DP),
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = host.dp(8).toFloat()
    }
    groups.filter { it.isNotEmpty() }.forEachIndexed { index, group ->
        if (index > 0) column.addView(ui.divider(host, scheme, verticalDp = 6))
        group.forEach { item ->
            column.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = host.dp(46)
                setPadding(host.dp(18), 0, host.dp(14), 0)
                isEnabled = item.enabled
                alpha = if (item.enabled) 1f else 0.38f
                background = rippleOf(scheme.onSurface, null, ColorDrawable(Color.WHITE))
                addView(TextView(ui).apply {
                    text = item.label
                    textSize = 15f
                    setTextColor(scheme.onSurface)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                item.checked?.let { checked ->
                    addView(ui.schemeSwitch(scheme).apply { isChecked = checked; isEnabled = item.enabled })
                }
                if (item.enabled) setOnClickListener {
                    popup.dismiss()
                    item.onClick()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    popup.showAsDropDown(anchor, -host.dp(MENU_WIDTH_DP) + anchor.width - host.dp(4), -anchor.height + host.dp(4), Gravity.NO_GRAVITY)
}

private const val MENU_WIDTH_DP = 248
