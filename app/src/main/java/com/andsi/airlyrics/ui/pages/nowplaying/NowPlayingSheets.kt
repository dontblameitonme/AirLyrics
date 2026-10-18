package com.andsi.airlyrics.ui.pages.nowplaying

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.theme.TonalScheme
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SongCommentSortUi
import com.andsi.airlyrics.ui.model.SongCommentUi
import com.andsi.airlyrics.ui.model.UiRequestHandle
import com.andsi.airlyrics.ui.pages.settings.themeAccentNameRes
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// ---------------------------------------------------------------------------------------------
// Shared sheet scaffolding
// ---------------------------------------------------------------------------------------------

internal class SheetScaffold(
    val dialog: BottomSheetDialog,
    val scheme: TonalScheme,
    val header: LinearLayout,
    val subtitle: TextView,
    val content: LinearLayout,
    val scroll: NestedScrollView
)

/**
 * Bottom sheet drawn entirely with the page scheme: transparent platform container, our own
 * rounded surface and drag handle, a title row and a scrollable body. Dismissed with the
 * activity.
 */
internal fun createSheet(
    host: MainUiHost,
    ui: Context,
    title: String,
    subtitle: String? = null,
    expanded: Boolean = false
): SheetScaffold {
    val scheme = host.nowPlayingScheme()
    val dialog = BottomSheetDialog(ui)
    val root = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        val radius = host.dp(28).toFloat()
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(scheme.surfaceContainerLow)
        }
        setPadding(0, 0, 0, host.dp(12))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
        view.setPadding(0, 0, 0, host.dp(12) + bars.bottom)
        insets
    }
    root.addView(View(ui).apply {
        background = roundedRect(scheme.onSurfaceVariant.withAlpha(100), host.dp(2).toFloat())
    }, LinearLayout.LayoutParams(host.dp(32), host.dp(4)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = host.dp(12)
        bottomMargin = host.dp(8)
    })
    val subtitleView = TextView(ui).apply {
        text = subtitle
        textSize = 13f
        setTextColor(scheme.onSurfaceVariant)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    val header = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(24), host.dp(4), host.dp(12), host.dp(8))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ui).apply {
                text = title
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(scheme.onSurface)
            })
            addView(subtitleView)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    root.addView(header)
    val content = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(16), 0, host.dp(16), host.dp(8))
    }
    val scroll = NestedScrollView(ui).apply {
        isFillViewport = true
        addView(content)
    }
    root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    dialog.setContentView(root)
    val clearContainer = {
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }
    clearContainer()
    dialog.setOnShowListener { clearContainer() }
    dialog.behavior.apply {
        if (expanded) {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        } else {
            peekHeight = (host.resources.displayMetrics.heightPixels * 0.62f).toInt()
            skipCollapsed = false
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }
    return SheetScaffold(dialog, scheme, header, subtitleView, content, scroll)
}

internal fun dismissWithActivity(host: MainUiHost, dialog: Dialog, onDismiss: () -> Unit = {}) {
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            if (dialog.isShowing) dialog.dismiss()
        }
    }
    host.activity.lifecycle.addObserver(observer)
    dialog.setOnDismissListener {
        host.activity.lifecycle.removeObserver(observer)
        onDismiss()
    }
}

internal fun messageView(host: MainUiHost, ui: Context, scheme: TonalScheme, text: String): TextView = TextView(ui).apply {
    this.text = text
    textSize = 14f
    gravity = Gravity.CENTER
    setTextColor(scheme.onSurfaceVariant)
    setPadding(host.dp(16), host.dp(28), host.dp(16), host.dp(16))
}

internal fun progressSpinner(host: MainUiHost, ui: Context, scheme: TonalScheme): View = FrameLayout(ui).apply {
    setPadding(0, host.dp(28), 0, host.dp(28))
    addView(CircularProgressIndicator(ui).apply {
        isIndeterminate = true
        setIndicatorColor(scheme.primary)
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
}

internal fun sectionLabel(host: MainUiHost, ui: Context, scheme: TonalScheme, text: String): TextView = TextView(ui).apply {
    this.text = text
    textSize = 13f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(scheme.primary)
    setPadding(host.dp(8), host.dp(20), host.dp(8), host.dp(2))
}

/** Outlined text field recolored with the scheme (the static theme would use its own primary). */
internal fun schemeTextField(
    host: MainUiHost,
    ui: Context,
    scheme: TonalScheme,
    label: String,
    value: String,
    helper: String? = null,
    configure: TextInputLayout.(TextInputEditText) -> Unit = {}
): Pair<TextInputLayout, TextInputEditText> {
    val focused = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())
    val layout = TextInputLayout(ui, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        hint = label
        helper?.let { helperText = it }
        setBoxStrokeColorStateList(ColorStateList(focused, intArrayOf(scheme.primary, scheme.outline)))
        hintTextColor = ColorStateList.valueOf(scheme.primary)
        defaultHintTextColor = ColorStateList.valueOf(scheme.onSurfaceVariant)
        setHelperTextColor(ColorStateList.valueOf(scheme.outline))
        setEndIconTintList(ColorStateList.valueOf(scheme.onSurfaceVariant))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
        }
    }
    val edit = TextInputEditText(layout.context).apply {
        setText(value)
        textSize = 15f
        setTextColor(scheme.onSurface)
        highlightColor = scheme.primary.withAlpha(70)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable?.mutate()?.setTint(scheme.primary)
            textSelectHandle?.mutate()?.setTint(scheme.primary)
            textSelectHandleLeft?.mutate()?.setTint(scheme.primary)
            textSelectHandleRight?.mutate()?.setTint(scheme.primary)
        }
    }
    layout.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    layout.configure(edit)
    return layout to edit
}

internal fun TextInputEditText.singleLine(variation: Int = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
    inputType = InputType.TYPE_CLASS_TEXT or variation
    isSingleLine = true
}

/** Row of right-aligned actions used at the bottom of small sheets. */
internal fun actionRow(host: MainUiHost, vararg buttons: View): LinearLayout = LinearLayout(buttons.first().context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
    setPadding(host.dp(8), host.dp(16), host.dp(8), 0)
    buttons.forEachIndexed { index, button ->
        addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (index > 0) marginStart = host.dp(8)
        })
    }
}

internal fun showConfirmSheet(
    host: MainUiHost,
    ui: Context,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit
) {
    val sheet = createSheet(host, ui, title)
    val scheme = sheet.scheme
    sheet.content.addView(TextView(ui).apply {
        text = message
        textSize = 14f
        setLineSpacing(0f, 1.2f)
        setTextColor(scheme.onSurfaceVariant)
        setPadding(host.dp(8), 0, host.dp(8), 0)
    })
    sheet.content.addView(
        actionRow(
            host,
            ui.pillButton(host, scheme, host.getString(R.string.ui_cancel)).apply { setOnClickListener { sheet.dialog.dismiss() } },
            ui.pillButton(host, scheme, confirmLabel, filled = true).apply {
                setOnClickListener {
                    sheet.dialog.dismiss()
                    onConfirm()
                }
            }
        )
    )
    dismissWithActivity(host, sheet.dialog)
    sheet.dialog.show()
}

// ---------------------------------------------------------------------------------------------
// NetEase Cloud Music comments
// ---------------------------------------------------------------------------------------------

internal fun showCommentsSheet(host: MainUiHost, ui: Context, track: NowPlayingTrackUi) {
    val sheet = createSheet(
        host,
        ui,
        host.getString(R.string.ui_comments_title),
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
    )
    val scheme = sheet.scheme
    var sort = SongCommentSortUi.HOT
    var request: UiRequestHandle? = null
    var nextOffset = 0
    val list = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    val footer = FrameLayout(ui)
    val tabs = mutableMapOf<SongCommentSortUi, TextView>()
    val underlines = mutableMapOf<SongCommentSortUi, View>()

    fun styleTabs() {
        tabs.forEach { (value, label) ->
            val selected = value == sort
            label.setTextColor(if (selected) scheme.onSurface else scheme.onSurfaceVariant)
            label.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            underlines[value]?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    fun load(reset: Boolean) {
        request?.cancel()
        if (reset) {
            nextOffset = 0
            list.removeAllViews()
        }
        footer.removeAllViews()
        footer.addView(progressSpinner(host, ui, scheme))
        val requestedSort = sort
        request = host.loadSongComments(track, requestedSort, nextOffset) { result ->
            if (requestedSort != sort) return@loadSongComments
            request = null
            footer.removeAllViews()
            result.onSuccess { page ->
                sheet.subtitle.text = host.getString(R.string.ui_comments_matched, page.matchedSong, formatCount(page.total))
                sheet.subtitle.visibility = View.VISIBLE
                page.comments.forEach { comment ->
                    if (list.childCount > 0) list.addView(ui.divider(host, scheme, startInsetDp = 48))
                    list.addView(commentRow(host, ui, scheme, comment))
                }
                nextOffset = page.nextOffset
                when {
                    page.hasMore -> footer.addView(
                        ui.pillButton(host, scheme, host.getString(R.string.ui_comments_load_more)).apply {
                            setOnClickListener { load(reset = false) }
                        },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                            topMargin = host.dp(8)
                        }
                    )
                    list.childCount == 0 -> footer.addView(messageView(host, ui, scheme, host.getString(R.string.ui_comments_empty)))
                }
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                footer.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(messageView(host, ui, scheme, host.getString(R.string.ui_comments_failed, message)))
                    addView(ui.pillButton(host, scheme, host.getString(R.string.ui_retry)).apply {
                        setOnClickListener { load(reset = false) }
                    })
                })
            }
        }
    }

    val tabRow = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(host.dp(8), 0, host.dp(8), 0)
    }
    listOf(SongCommentSortUi.HOT to R.string.ui_comments_hot, SongCommentSortUi.LATEST to R.string.ui_comments_latest)
        .forEach { (value, labelRes) ->
            val label = TextView(ui).apply {
                setText(labelRes)
                textSize = 15f
                setPadding(0, host.dp(8), 0, host.dp(8))
            }
            val underline = View(ui).apply { background = roundedRect(scheme.primary, host.dp(1).toFloat()) }
            tabs[value] = label
            underlines[value] = underline
            tabRow.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(label)
                addView(underline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(2)))
                setOnClickListener {
                    if (sort == value) return@setOnClickListener
                    sort = value
                    styleTabs()
                    load(reset = true)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = host.dp(24)
            })
        }
    styleTabs()

    sheet.content.addView(tabRow)
    sheet.content.addView(ui.divider(host, scheme))
    sheet.content.addView(list)
    sheet.content.addView(footer)
    dismissWithActivity(host, sheet.dialog) { request?.cancel() }
    load(reset = true)
    sheet.dialog.show()
}

private fun commentRow(host: MainUiHost, ui: Context, scheme: TonalScheme, comment: SongCommentUi): View =
    LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(8), host.dp(14), host.dp(8), host.dp(14))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(letterAvatar(host, ui, scheme, comment.nickname))
            addView(TextView(ui).apply {
                text = comment.nickname.ifBlank { "—" }
                textSize = 13f
                setTextColor(scheme.outline)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = host.dp(12) })
            if (comment.likedCount > 0L) addView(TextView(ui).apply {
                text = formatCount(comment.likedCount)
                textSize = 12f
                setTextColor(scheme.outline)
                compoundDrawablePadding = host.dp(4)
                val heart = ui.getDrawable(R.drawable.ic_air_favorite_border)?.mutate()?.apply {
                    setTint(scheme.outline)
                    setBounds(0, 0, host.dp(14), host.dp(14))
                }
                setCompoundDrawablesRelative(heart, null, null, null)
            })
        })
        val indent = host.dp(40)
        addView(TextView(ui).apply {
            text = comment.content
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            setTextColor(scheme.onSurface)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = indent
            topMargin = host.dp(2)
        })
        if (!comment.repliedContent.isNullOrBlank()) addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(ui).apply { setBackgroundColor(scheme.outlineVariant) }, LinearLayout.LayoutParams(host.dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(ui).apply {
                text = comment.repliedNickname?.let { "@$it：${comment.repliedContent}" } ?: comment.repliedContent
                textSize = 13f
                setLineSpacing(0f, 1.2f)
                setTextColor(scheme.onSurfaceVariant)
                setPadding(host.dp(10), host.dp(2), 0, host.dp(2))
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = indent
            topMargin = host.dp(8)
        })
        val meta = listOfNotNull(comment.timeText.takeIf { it.isNotBlank() }, comment.location).joinToString(" · ")
        if (meta.isNotBlank()) addView(TextView(ui).apply {
            text = meta
            textSize = 11f
            setTextColor(scheme.outline)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = indent
            topMargin = host.dp(6)
        })
    }

private fun letterAvatar(host: MainUiHost, ui: Context, scheme: TonalScheme, name: String): View = TextView(ui).apply {
    val size = host.dp(28)
    layoutParams = LinearLayout.LayoutParams(size, size)
    gravity = Gravity.CENTER
    text = name.trim().firstOrNull()?.uppercase() ?: "?"
    textSize = 12f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(scheme.primary)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(scheme.primary.withAlpha(46))
    }
}

// ---------------------------------------------------------------------------------------------
// Lyrics offset
// ---------------------------------------------------------------------------------------------

internal fun showLyricsOffsetSheet(host: MainUiHost, ui: Context, onChanged: () -> Unit) {
    val sheet = createSheet(host, ui, host.getString(R.string.ui_lyrics_offset), host.getString(R.string.ui_offset_per_song_hint))
    val scheme = sheet.scheme
    val summary = TextView(ui).apply {
        textSize = 32f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(scheme.onSurface)
        fontFeatureSettings = "tnum"
        text = host.uiActions.currentLyricsOffsetSummary()
        setPadding(0, host.dp(12), 0, host.dp(16))
    }
    fun refresh() {
        summary.text = host.uiActions.currentLyricsOffsetSummary()
        onChanged()
    }
    val buttons = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        listOf(-500L to "-0.5s", -100L to "-0.1s", 100L to "+0.1s", 500L to "+0.5s").forEach { (delta, label) ->
            addView(TextView(ui).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                setTextColor(scheme.onSecondaryContainer)
                val radius = host.dp(20).toFloat()
                background = rippleOf(scheme.onSecondaryContainer, roundedRect(scheme.secondaryContainer, radius))
                minHeight = host.dp(44)
                setOnClickListener {
                    host.uiActions.adjustLyricsOffsetForCurrentMedia(delta)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = host.dp(4)
                marginEnd = host.dp(4)
            })
        }
    }
    sheet.content.addView(summary)
    sheet.content.addView(buttons)
    sheet.content.addView(
        actionRow(
            host,
            ui.pillButton(host, scheme, host.getString(R.string.ui_reset)).apply {
                setOnClickListener {
                    host.uiActions.resetLyricsOffsetForCurrentMedia()
                    refresh()
                }
            },
            ui.pillButton(host, scheme, host.getString(R.string.ui_close), filled = true).apply {
                setOnClickListener { sheet.dialog.dismiss() }
            }
        )
    )
    dismissWithActivity(host, sheet.dialog)
    sheet.dialog.show()
}

// ---------------------------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------------------------

internal fun showThemeSheet(host: MainUiHost, ui: Context) {
    val sheet = createSheet(host, ui, host.getString(R.string.ui_theme_colors))
    val scheme = sheet.scheme
    sheet.content.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_theme_mode)).apply {
        setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(8))
    })
    val currentMode = host.themeDarkOverride()
    val modes = listOf(
        null to R.string.ui_theme_follow_system,
        false to R.string.ui_theme_light,
        true to R.string.ui_theme_dark
    )
    sheet.content.addView(LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        val radius = host.dp(20).toFloat()
        background = roundedRect(scheme.surfaceContainerHighest, radius)
        setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4))
        modes.forEach { (mode, label) ->
            val selected = mode == currentMode
            addView(TextView(ui).apply {
                setText(label)
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = host.dp(36)
                typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                setTextColor(if (selected) scheme.surface else scheme.onSurfaceVariant)
                background = rippleOf(scheme.onSurface, roundedRect(if (selected) scheme.onSurface else 0, radius), roundedRect(Color.WHITE, radius))
                setOnClickListener {
                    sheet.dialog.dismiss()
                    if (!selected) host.setThemeDarkOverride(mode)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = host.dp(8)
        marginEnd = host.dp(8)
    })

    sheet.content.addView(sectionLabel(host, ui, scheme, host.getString(R.string.ui_accent_color)))
    val current = host.themeAccent()
    host.availableThemeAccents().chunked(THEME_SWATCHES_PER_ROW).forEach { row ->
        sheet.content.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, host.dp(8), 0, 0)
            row.forEach { accent -> addView(themeSwatch(host, ui, scheme, accent, accent == current) { sheet.dialog.dismiss() }) }
            repeat(THEME_SWATCHES_PER_ROW - row.size) { addView(View(ui), LinearLayout.LayoutParams(0, 0, 1f)) }
        })
    }
    sheet.content.addView(TextView(ui).apply {
        setText(R.string.ui_theme_tonal_hint)
        textSize = 12f
        setTextColor(scheme.outline)
        setPadding(host.dp(8), host.dp(16), host.dp(8), 0)
    })
    dismissWithActivity(host, sheet.dialog)
    sheet.dialog.show()
}

private fun themeSwatch(
    host: MainUiHost,
    ui: Context,
    scheme: TonalScheme,
    accent: ThemeAccent,
    selected: Boolean,
    onPicked: () -> Unit
): View = LinearLayout(ui).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    val color = AirLyricsTheme.palette(host.isDarkTheme(), accent, host.themeSeedFor(accent)).accent
    val size = host.dp(40)
    addView(FrameLayout(ui).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke(host.dp(3), scheme.onSurface)
        }
        val badge = when {
            selected -> R.drawable.ic_air_check
            accent == ThemeAccent.DYNAMIC -> R.drawable.ic_air_auto_awesome
            accent == ThemeAccent.COVER -> R.drawable.ic_air_music_note
            else -> 0
        }
        if (badge != 0) addView(android.widget.ImageView(ui).apply {
            setImageResource(badge)
            imageTintList = ColorStateList.valueOf(
                if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK.withAlpha(200) else Color.WHITE
            )
        }, FrameLayout.LayoutParams(host.dp(20), host.dp(20), Gravity.CENTER))
    }, LinearLayout.LayoutParams(size, size))
    addView(TextView(ui).apply {
        setText(themeAccentNameRes(accent))
        textSize = 10f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextColor(if (selected) scheme.onSurface else scheme.onSurfaceVariant)
        setPadding(0, host.dp(4), 0, 0)
    })
    setPadding(0, host.dp(4), 0, host.dp(4))
    background = rippleOf(scheme.onSurface, null, roundedRect(Color.WHITE, host.dp(12).toFloat()))
    contentDescription = host.getString(themeAccentNameRes(accent))
    setOnClickListener {
        onPicked()
        if (!selected) host.uiActions.selectThemeAccent(accent)
    }
    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}

private const val THEME_SWATCHES_PER_ROW = 6
