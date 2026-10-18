package com.andsi.airlyrics.ui.pages.nowplaying

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.AiIntroSettingsUi
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SongCommentSortUi
import com.andsi.airlyrics.ui.model.SongCommentUi
import com.andsi.airlyrics.ui.model.UiRequestHandle
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// ---------------------------------------------------------------------------------------------
// Shared sheet scaffolding
// ---------------------------------------------------------------------------------------------

private class SheetScaffold(
    val dialog: BottomSheetDialog,
    val header: LinearLayout,
    val content: LinearLayout,
    val scroll: NestedScrollView
)

/** Bottom sheet with a title row and a scrollable body; dismissed with the activity. */
private fun createSheet(host: MainUiHost, ui: Context, title: String, subtitle: String?): SheetScaffold {
    val dialog = BottomSheetDialog(ui)
    val root = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, host.dp(12))
    }
    val header = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(24), host.dp(4), host.dp(12), host.dp(8))
    }
    header.addView(LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(ui).apply {
            text = title
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(host.colorTextStrong)
        })
        if (!subtitle.isNullOrBlank()) addView(TextView(ui).apply {
            text = subtitle
            textSize = 13f
            setTextColor(host.colorTextMuted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = SUBTITLE_TAG
        })
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
    dialog.behavior.apply {
        peekHeight = (host.resources.displayMetrics.heightPixels * 0.62f).toInt()
        skipCollapsed = false
        state = BottomSheetBehavior.STATE_COLLAPSED
    }
    return SheetScaffold(dialog, header, content, scroll)
}

private fun dismissWithActivity(host: MainUiHost, dialog: Dialog, onDismiss: () -> Unit = {}) {
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

private fun messageView(host: MainUiHost, ui: Context, text: String): TextView = TextView(ui).apply {
    this.text = text
    textSize = 14f
    gravity = Gravity.CENTER
    setTextColor(host.colorTextMuted)
    setPadding(host.dp(16), host.dp(32), host.dp(16), host.dp(32))
}

private fun Context.textButton(host: MainUiHost, label: Int): MaterialButton =
    MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(label)
        setTextColor(host.colorAccent)
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 48))
    }

private fun Context.tonalButton(host: MainUiHost, label: Int): MaterialButton =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
        setText(label)
        backgroundTintList = ColorStateList.valueOf(host.colorAccentSoft)
        setTextColor(host.colorAccent)
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 48))
    }

private fun progressSpinner(host: MainUiHost, ui: Context): View = FrameLayout(ui).apply {
    setPadding(0, host.dp(28), 0, host.dp(28))
    addView(CircularProgressIndicator(ui).apply {
        isIndeterminate = true
        setIndicatorColor(host.colorAccent)
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
    val subtitle = sheet.header.findViewWithTag<TextView>(SUBTITLE_TAG)
    var sort = SongCommentSortUi.HOT
    var request: UiRequestHandle? = null
    var nextOffset = 0
    val list = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    val footer = FrameLayout(ui)

    val toggle = MaterialButtonToggleGroup(ui).apply {
        isSingleSelection = true
        isSelectionRequired = true
        setPadding(0, 0, 0, host.dp(8))
    }
    listOf(SongCommentSortUi.HOT to R.string.ui_comments_hot, SongCommentSortUi.LATEST to R.string.ui_comments_latest)
        .forEach { (value, label) ->
            toggle.addView(
                MaterialButton(ui, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    tag = value
                    setText(label)
                    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                    backgroundTintList = ColorStateList(states, intArrayOf(host.colorAccentSoft, 0))
                    setTextColor(ColorStateList(states, intArrayOf(host.colorAccent, host.colorText)))
                    strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorTextMuted, 90))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

    fun load(reset: Boolean) {
        request?.cancel()
        if (reset) {
            nextOffset = 0
            list.removeAllViews()
        }
        footer.removeAllViews()
        footer.addView(progressSpinner(host, ui))
        val requestedSort = sort
        request = host.loadSongComments(track, requestedSort, nextOffset) { result ->
            if (requestedSort != sort) return@loadSongComments
            footer.removeAllViews()
            result.onSuccess { page ->
                subtitle?.text = host.getString(R.string.ui_comments_matched, page.matchedSong, formatCount(page.total))
                page.comments.forEach { list.addView(commentCard(host, ui, it)) }
                nextOffset = page.nextOffset
                when {
                    page.hasMore -> footer.addView(ui.textButton(host, R.string.ui_comments_load_more).apply {
                        setOnClickListener { load(reset = false) }
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    list.childCount == 0 -> footer.addView(messageView(host, ui, host.getString(R.string.ui_comments_empty)))
                }
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                footer.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(messageView(host, ui, host.getString(R.string.ui_comments_failed, message)))
                    addView(ui.tonalButton(host, R.string.ui_retry).apply { setOnClickListener { load(reset = false) } })
                })
            }
        }
    }

    toggle.addOnButtonCheckedListener { group, checkedId, isChecked ->
        if (!isChecked) return@addOnButtonCheckedListener
        val selected = group.findViewById<View>(checkedId)?.tag as? SongCommentSortUi ?: return@addOnButtonCheckedListener
        if (selected == sort && list.childCount > 0) return@addOnButtonCheckedListener
        sort = selected
        load(reset = true)
    }

    sheet.content.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    sheet.content.addView(list)
    sheet.content.addView(footer)
    dismissWithActivity(host, sheet.dialog) { request?.cancel() }
    toggle.check(toggle.getChildAt(0).id)
    if (list.childCount == 0 && request == null) load(reset = true)
    sheet.dialog.show()
}

private fun commentCard(host: MainUiHost, ui: Context, comment: SongCommentUi): View =
    MaterialCardView(ui, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = host.dp(8)
        }
        radius = host.dp(16).toFloat()
        setCardBackgroundColor(ColorUtils.setAlphaComponent(host.colorAccentSoft, 150))
        val body = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
        }
        body.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(letterAvatar(host, ui, comment.nickname))
            addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ui).apply {
                    text = comment.nickname.ifBlank { "—" }
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(host.colorTextStrong)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(ui).apply {
                    text = listOfNotNull(comment.timeText.takeIf { it.isNotBlank() }, comment.location).joinToString(" · ")
                    textSize = 11f
                    setTextColor(host.colorTextMuted)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = host.dp(10) })
            if (comment.likedCount > 0L) addView(TextView(ui).apply {
                text = formatCount(comment.likedCount)
                textSize = 12f
                setTextColor(host.colorTextMuted)
                compoundDrawablePadding = host.dp(4)
                val heart = ui.getDrawable(R.drawable.ic_air_favorite_border)?.mutate()?.apply {
                    setTint(host.colorTextMuted)
                    setBounds(0, 0, host.dp(16), host.dp(16))
                }
                setCompoundDrawablesRelative(heart, null, null, null)
            })
        })
        body.addView(TextView(ui).apply {
            text = comment.content
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setTextColor(host.colorTextStrong)
            setTextIsSelectable(true)
            setPadding(0, host.dp(8), 0, 0)
        })
        if (!comment.repliedContent.isNullOrBlank()) body.addView(TextView(ui).apply {
            text = comment.repliedNickname?.let { "@$it：${comment.repliedContent}" } ?: comment.repliedContent
            textSize = 13f
            setTextColor(host.colorText)
            background = GradientDrawable().apply {
                cornerRadius = host.dp(10).toFloat()
                setColor(ColorUtils.setAlphaComponent(host.colorTextMuted, 28))
            }
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = host.dp(8)
            }
        })
        addView(body)
    }

private fun letterAvatar(host: MainUiHost, ui: Context, name: String): View = TextView(ui).apply {
    val size = host.dp(32)
    layoutParams = LinearLayout.LayoutParams(size, size)
    gravity = Gravity.CENTER
    text = name.trim().firstOrNull()?.uppercase() ?: "?"
    textSize = 14f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(host.colorAccent)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(host.colorAccentSoft)
    }
}

// ---------------------------------------------------------------------------------------------
// AI song introduction
// ---------------------------------------------------------------------------------------------

internal fun showAiIntroSheet(host: MainUiHost, ui: Context, track: NowPlayingTrackUi, lyricsText: String) {
    val sheet = createSheet(
        host,
        ui,
        host.getString(R.string.ui_ai_intro_title),
        listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
    )
    var request: UiRequestHandle? = null
    val output = StringBuilder()
    val progress = LinearProgressIndicator(ui).apply {
        isIndeterminate = true
        setIndicatorColor(host.colorAccent)
        trackColor = ColorUtils.setAlphaComponent(host.colorAccent, 40)
        visibility = View.GONE
    }
    val body = TextView(ui).apply {
        textSize = 15f
        setLineSpacing(0f, 1.3f)
        setTextColor(host.colorTextStrong)
        setTextIsSelectable(true)
        setPadding(host.dp(8), host.dp(12), host.dp(8), host.dp(8))
    }
    val footer = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    fun generate() {
        request?.cancel()
        output.setLength(0)
        body.text = ""
        footer.removeAllViews()
        val settings = host.aiIntroSettings()
        if (settings.apiKey.isBlank()) {
            progress.visibility = View.GONE
            footer.addView(messageView(host, ui, host.getString(R.string.ui_ai_need_key)))
            footer.addView(ui.tonalButton(host, R.string.ui_ai_settings).apply {
                setOnClickListener { showAiSettingsDialog(host, ui) { generate() } }
            })
            return
        }
        progress.visibility = View.VISIBLE
        request = host.streamAiSongIntro(
            track,
            lyricsText,
            onDelta = { delta ->
                output.append(delta)
                body.text = renderLightMarkdown(output.toString())
            },
            onFinished = { error ->
                progress.visibility = View.GONE
                request = null
                if (error != null) {
                    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    footer.addView(messageView(host, ui, host.getString(R.string.ui_ai_failed, message)))
                } else {
                    footer.addView(TextView(ui).apply {
                        setText(R.string.ui_ai_disclaimer)
                        textSize = 11f
                        setTextColor(host.colorTextMuted)
                        setPadding(0, host.dp(8), 0, 0)
                    })
                }
            }
        )
    }

    val settingsButton = ui.materialIconButton(host, R.drawable.ic_air_tune, R.string.ui_ai_settings).apply {
        setOnClickListener { showAiSettingsDialog(host, ui) { generate() } }
    }
    val regenerateButton = ui.materialIconButton(host, R.drawable.ic_air_refresh, R.string.ui_ai_regenerate).apply {
        setOnClickListener { generate() }
    }
    sheet.header.addView(regenerateButton)
    sheet.header.addView(settingsButton)
    sheet.content.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        marginStart = host.dp(8)
        marginEnd = host.dp(8)
    })
    sheet.content.addView(body)
    sheet.content.addView(footer)
    dismissWithActivity(host, sheet.dialog) { request?.cancel() }
    generate()
    sheet.dialog.show()
}

internal fun showAiSettingsDialog(host: MainUiHost, ui: Context, onSaved: (() -> Unit)? = null) {
    val current = host.aiIntroSettings()
    fun field(label: Int, value: String, helper: Int? = null, configure: TextInputLayout.(TextInputEditText) -> Unit = {}): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(ui, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = host.getString(label)
            helper?.let { helperText = host.getString(it) }
            boxStrokeColor = host.colorAccent
            hintTextColor = ColorStateList.valueOf(host.colorAccent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = host.dp(12)
            }
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            textSize = 15f
        }
        layout.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.configure(edit)
        return layout to edit
    }

    val (baseUrlLayout, baseUrl) = field(R.string.ui_ai_base_url, current.baseUrl, R.string.ui_ai_base_url_helper) { edit ->
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        edit.isSingleLine = true
    }
    val (modelLayout, model) = field(R.string.ui_ai_model, current.model) { edit ->
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.isSingleLine = true
    }
    val (apiKeyLayout, apiKey) = field(R.string.ui_ai_api_key, current.apiKey, R.string.ui_ai_api_key_helper) { edit ->
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        edit.isSingleLine = true
        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    val (promptLayout, prompt) = field(R.string.ui_ai_prompt, current.prompt, R.string.ui_ai_prompt_helper) { edit ->
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.minLines = 4
        edit.maxLines = 10
        edit.gravity = Gravity.TOP or Gravity.START
    }

    val form = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(24), host.dp(4), host.dp(24), 0)
        listOf(baseUrlLayout, modelLayout, apiKeyLayout, promptLayout).forEach(::addView)
    }
    val dialog = MaterialAlertDialogBuilder(ui)
        .setTitle(R.string.ui_ai_settings)
        .setView(NestedScrollView(ui).apply { addView(form) })
        .setNegativeButton(R.string.ui_cancel, null)
        .setNeutralButton(R.string.ui_ai_reset_prompt, null)
        .setPositiveButton(R.string.ui_save, null)
        .create()
    dialog.setOnShowListener {
        dialog.tintButtons(host)
        dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener { prompt.setText(current.defaultPrompt) }
        dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
            val url = baseUrl.text?.toString().orEmpty().trim()
            if (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
                baseUrlLayout.error = host.getString(R.string.ui_ai_base_url_invalid)
                return@setOnClickListener
            }
            host.saveAiIntroSettings(
                AiIntroSettingsUi(
                    baseUrl = url,
                    model = model.text?.toString().orEmpty(),
                    apiKey = apiKey.text?.toString().orEmpty(),
                    prompt = prompt.text?.toString().orEmpty(),
                    defaultPrompt = current.defaultPrompt
                )
            )
            dialog.dismiss()
            onSaved?.invoke()
        }
    }
    dismissWithActivity(host, dialog)
    dialog.show()
}

// ---------------------------------------------------------------------------------------------
// Lyrics offset
// ---------------------------------------------------------------------------------------------

internal fun showLyricsOffsetDialog(host: MainUiHost, ui: Context, onChanged: () -> Unit) {
    val summary = TextView(ui).apply {
        textSize = 28f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(host.colorTextStrong)
        fontFeatureSettings = "tnum"
        text = host.uiActions.currentLyricsOffsetSummary()
    }
    fun refresh() {
        summary.text = host.uiActions.currentLyricsOffsetSummary()
        onChanged()
    }
    val buttons = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, host.dp(16), 0, 0)
        listOf(-500L to "-0.5s", -100L to "-0.1s", 100L to "+0.1s", 500L to "+0.5s").forEach { (delta, label) ->
            addView(ui.tonalButton(host, R.string.ui_lyrics_offset).apply {
                text = label
                minWidth = 0
                minimumWidth = 0
                setPadding(host.dp(12), 0, host.dp(12), 0)
                setOnClickListener {
                    host.uiActions.adjustLyricsOffsetForCurrentMedia(delta)
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = host.dp(3)
                marginEnd = host.dp(3)
            })
        }
    }
    val content = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(20), host.dp(8), host.dp(20), 0)
        addView(summary)
        addView(TextView(ui).apply {
            setText(R.string.ui_offset_per_song_hint)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(host.colorTextMuted)
        })
        addView(buttons)
    }
    val dialog = MaterialAlertDialogBuilder(ui)
        .setTitle(R.string.ui_lyrics_offset)
        .setView(content)
        .setNeutralButton(R.string.ui_reset, null)
        .setPositiveButton(R.string.ui_close, null)
        .create()
    dialog.setOnShowListener {
        dialog.tintButtons(host)
        dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener {
            host.uiActions.resetLyricsOffsetForCurrentMedia()
            refresh()
        }
    }
    dismissWithActivity(host, dialog)
    dialog.show()
}

private const val SUBTITLE_TAG = "sheet-subtitle"
