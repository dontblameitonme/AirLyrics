package com.andsi.airlyrics.ui.pages.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingLineUi
import com.andsi.airlyrics.ui.model.NowPlayingListener
import com.andsi.airlyrics.ui.model.NowPlayingLyricsUi
import com.andsi.airlyrics.ui.model.NowPlayingSession
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal fun createNowPlayingPage(activity: MainUiHost): View = NowPlayingPageView(activity, activity.nowPlayingThemedContext())

/**
 * In-app lyrics page (Material 3): top app bar with track info and actions, scrolling lyrics,
 * progress slider and transport controls. Less frequent actions live in the overflow menu.
 *
 * The page owns a [NowPlayingSession] only while attached, so leaving the tab or rebuilding
 * the page releases the media-session callback and any pending lyrics lookup.
 */
@SuppressLint("ViewConstructor")
private class NowPlayingPageView(
    private val host: MainUiHost,
    private val ui: Context
) : FrameLayout(ui), NowPlayingListener {
    private var session: NowPlayingSession? = null
    private var track: NowPlayingTrackUi? = null
    private var lines: List<NowPlayingLineUi> = emptyList()
    private var userSeeking = false
    private var lastShownSecond = -1L
    private var frameScheduled = false

    private val artwork = ImageView(ui)
    private val title = TextView(ui)
    private val subtitle = TextView(ui)
    private val commentsButton = ui.materialIconButton(host, R.drawable.ic_air_chat_bubble, R.string.ui_comments)
    private val aiButton = ui.materialIconButton(host, R.drawable.ic_air_auto_awesome, R.string.ui_ai_intro)
    private val moreButton = ui.materialIconButton(host, R.drawable.ic_air_more_vert, R.string.ui_more_options)
    private val stage = LyricsStageView(ui)
    private val attribution = TextView(ui)
    private val slider = Slider(ui)
    private val elapsed = TextView(ui)
    private val remaining = TextView(ui)
    private val previous = transportButton(R.drawable.ic_air_skip_previous, R.string.ui_previous_track)
    private val next = transportButton(R.drawable.ic_air_skip_next, R.string.ui_next_track)
    private val playPause = FloatingActionButton(ui)

    private val frame = object : Runnable {
        override fun run() {
            frameScheduled = false
            val current = session ?: return
            stage.updatePosition(current.lyricsPositionMs())
            updateProgress(current.playbackPositionMs())
            scheduleFrame()
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val column = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        column.addView(createTopBar())
        column.addView(stage.apply {
            setTextColor(host.colorTextStrong)
            showTranslation = host.nowPlayingTranslationVisible()
            onLineTapped = { line -> session?.seekToLyricLine(line.startMs) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        column.addView(attribution.apply {
            textSize = 11f
            setTextColor(host.colorTextMuted)
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(host.dp(24), 0, host.dp(24), host.dp(4))
        })
        column.addView(createProgress())
        column.addView(createControls())
        addView(column)
        showNoMedia()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (session == null) session = host.openNowPlayingSession(this)
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        frameScheduled = false
        session?.close()
        session = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleFrame() else {
            removeCallbacks(frame)
            frameScheduled = false
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled || session == null || windowVisibility != VISIBLE) return
        frameScheduled = true
        // Every frame while playing for smooth lyrics; a slow tick is enough while paused.
        if (track?.isPlaying == true) postOnAnimation(frame) else postDelayed(frame, PAUSED_TICK_MS)
    }

    override fun onTrackChanged(track: NowPlayingTrackUi?) {
        this.track = track
        if (track == null) {
            showNoMedia()
            return
        }
        title.text = track.title.ifBlank { host.getString(R.string.ui_unknown_song) }
        subtitle.text = listOf(track.artist, track.sourceName).filter { it.isNotBlank() }.joinToString(" · ")
        if (track.artwork != null) {
            artwork.setImageBitmap(track.artwork)
            artwork.imageTintList = null
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setPadding(0, 0, 0, 0)
        } else {
            showPlaceholderArtwork()
        }
        setSliderRange(track.durationMs)
        slider.isEnabled = track.canSeek && track.durationMs > 0L
        setEnabled(previous, track.canSkipPrevious)
        setEnabled(next, track.canSkipNext)
        setEnabled(playPause, track.canPlayPause)
        setEnabled(commentsButton, track.title.isNotBlank())
        setEnabled(aiButton, track.title.isNotBlank())
        playPause.setImageResource(if (track.isPlaying) R.drawable.ic_air_pause else R.drawable.ic_air_play)
        playPause.contentDescription = host.getString(if (track.isPlaying) R.string.ui_pause else R.string.ui_play)
        lastShownSecond = -1L
    }

    override fun onLyricsChanged(lyrics: NowPlayingLyricsUi) {
        when (lyrics) {
            is NowPlayingLyricsUi.Loading -> {
                lines = emptyList()
                stage.showMessage(lyrics.message)
                attribution.visibility = GONE
            }
            is NowPlayingLyricsUi.Message -> {
                lines = emptyList()
                stage.showMessage(lyrics.message)
                attribution.visibility = GONE
            }
            is NowPlayingLyricsUi.Lines -> {
                lines = lyrics.lines
                stage.showLines(lyrics.lines)
                // Provider credit is a usage requirement for some sources (e.g. Spicy Lyrics).
                attribution.text = lyrics.attribution
                attribution.visibility = if (lyrics.attribution.isNullOrBlank()) GONE else VISIBLE
            }
        }
    }

    private fun showNoMedia() {
        lines = emptyList()
        title.text = host.getString(R.string.ui_no_media_detected_yet)
        subtitle.text = host.getString(R.string.ui_media_streams_empty_hint)
        showPlaceholderArtwork()
        stage.showMessage(host.getString(R.string.ui_now_playing_waiting))
        attribution.visibility = GONE
        slider.isEnabled = false
        setSliderRange(0L)
        elapsed.text = formatTime(0L)
        remaining.text = formatTime(0L)
        listOf(previous, playPause, next, commentsButton, aiButton).forEach { setEnabled(it, false) }
        playPause.setImageResource(R.drawable.ic_air_play)
    }

    private fun setSliderRange(durationMs: Long) {
        val to = durationMs.coerceAtLeast(1L).toFloat()
        if (slider.valueTo == to) return
        slider.value = 0f
        slider.valueTo = to
    }

    private fun updateProgress(positionMs: Long) {
        val current = track ?: return
        if (!userSeeking) slider.value = positionMs.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        val second = positionMs / 1000L
        if (second != lastShownSecond) {
            lastShownSecond = second
            if (!userSeeking) elapsed.text = formatTime(positionMs)
            remaining.text = if (current.durationMs > 0L) "-" + formatTime(current.durationMs - positionMs) else ""
        }
    }

    private fun createTopBar(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(16), host.dp(12), host.dp(4), host.dp(8))
        val size = host.dp(52)
        addView(artwork.apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, host.dp(12).toFloat())
                }
            }
        })
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = host.dp(14)
                marginEnd = host.dp(4)
            }
            addView(title.apply {
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(host.colorTextStrong)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(subtitle.apply {
                textSize = 13f
                setTextColor(host.colorTextMuted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        commentsButton.setOnClickListener { track?.let { showCommentsSheet(host, ui, it) } }
        aiButton.setOnClickListener {
            track?.let { current -> showAiIntroSheet(host, ui, current, lines.joinToString("\n") { it.text }) }
        }
        moreButton.setOnClickListener { showOverflowMenu(it) }
        addView(commentsButton)
        addView(aiButton)
        addView(moreButton)
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(ui, anchor, Gravity.END)
        val menu = popup.menu
        menu.add(Menu.NONE, MENU_TRANSLATION, 0, R.string.ui_show_translation).apply {
            isCheckable = true
            isChecked = stage.showTranslation
            isEnabled = lines.any { !it.translation.isNullOrBlank() } || !stage.showTranslation
        }
        menu.add(Menu.NONE, MENU_OFFSET, 1, R.string.ui_lyrics_offset).isEnabled = track != null
        menu.add(Menu.NONE, MENU_RESEARCH, 2, R.string.ui_search_online_again).isEnabled = track != null
        menu.add(
            Menu.NONE,
            MENU_FLOATING,
            3,
            if (host.quickFloatingDesiredVisible) R.string.ui_hide_floating_lyrics else R.string.ui_show_floating_lyrics
        )
        menu.add(Menu.NONE, MENU_AI_SETTINGS, 4, R.string.ui_ai_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TRANSLATION -> {
                    val visible = !stage.showTranslation
                    stage.showTranslation = visible
                    host.setNowPlayingTranslationVisible(visible)
                }
                MENU_OFFSET -> showLyricsOffsetDialog(host, ui) { session?.refreshLyricsOffset() }
                MENU_RESEARCH -> confirmSearchOnline()
                MENU_FLOATING -> if (host.quickFloatingDesiredVisible) {
                    host.uiActions.hideFloatingLyrics()
                } else {
                    host.uiActions.showFloatingLyrics()
                }
                MENU_AI_SETTINGS -> showAiSettingsDialog(host, ui)
            }
            true
        }
        popup.show()
    }

    private fun confirmSearchOnline() {
        MaterialAlertDialogBuilder(ui)
            .setTitle(R.string.ui_search_online_again_confirm)
            .setMessage(R.string.ui_search_online_replace_cache_msg)
            .setNegativeButton(R.string.ui_cancel, null)
            .setPositiveButton(R.string.ui_search_online_again) { _, _ -> host.uiActions.searchOnlineLyricsForCurrentMedia() }
            .show()
            .tintButtons(host)
    }

    private fun createProgress(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(8), host.dp(4), host.dp(8), 0)
        addView(slider.apply {
            valueFrom = 0f
            valueTo = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            trackActiveTintList = ColorStateList.valueOf(host.colorAccent)
            thumbTintList = ColorStateList.valueOf(host.colorAccent)
            trackInactiveTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorTextMuted, 70))
            haloTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 40))
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) elapsed.text = formatTime(value.toLong())
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(bar: Slider) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: Slider) {
                    userSeeking = false
                    session?.seekToPlayback(bar.value.toLong())
                }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(16), 0, host.dp(16), 0)
            addView(elapsed.apply { timeLabel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(remaining.apply {
                timeLabel()
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun TextView.timeLabel() {
        textSize = 12f
        setTextColor(host.colorTextMuted)
        fontFeatureSettings = "tnum"
    }

    private fun createControls(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, host.dp(4), 0, host.dp(16))
        previous.setOnClickListener { session?.skipToPrevious() }
        playPause.setOnClickListener { session?.togglePlayPause() }
        next.setOnClickListener { session?.skipToNext() }
        addView(previous)
        addView(playPause.apply {
            customSize = host.dp(72)
            setMaxImageSize(host.dp(36))
            backgroundTintList = ColorStateList.valueOf(host.colorAccent)
            imageTintList = ColorStateList.valueOf(host.colorIconOnAccent)
            rippleColor = ColorUtils.setAlphaComponent(host.colorIconOnAccent, 60)
            compatElevation = host.dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = host.dp(32)
                marginEnd = host.dp(32)
            }
        })
        addView(next)
    }

    private fun transportButton(icon: Int, label: Int): MaterialButton =
        ui.materialIconButton(host, icon, label).apply {
            iconSize = host.dp(34)
            val box = host.dp(60)
            minimumWidth = box
            minimumHeight = box
            minWidth = box
            minHeight = box
            setPadding(0, 0, 0, 0)
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            insetTop = 0
            insetBottom = 0
        }

    private fun showPlaceholderArtwork() {
        artwork.setImageResource(R.drawable.ic_air_music_note)
        artwork.imageTintList = ColorStateList.valueOf(host.colorAccent)
        artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
        artwork.background = GradientDrawable().apply { setColor(host.colorAccentSoft) }
        val inset = host.dp(14)
        artwork.setPadding(inset, inset, inset, inset)
    }

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val PAUSED_TICK_MS = 250L
        const val MENU_TRANSLATION = 1
        const val MENU_OFFSET = 2
        const val MENU_RESEARCH = 3
        const val MENU_FLOATING = 4
        const val MENU_AI_SETTINGS = 5
    }
}
