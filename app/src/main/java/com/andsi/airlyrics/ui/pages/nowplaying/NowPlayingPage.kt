package com.andsi.airlyrics.ui.pages.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.theme.TonalScheme
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingLineUi
import com.andsi.airlyrics.ui.model.NowPlayingListener
import com.andsi.airlyrics.ui.model.NowPlayingLyricsUi
import com.andsi.airlyrics.ui.model.NowPlayingSession
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SongCommentSortUi
import com.andsi.airlyrics.ui.model.UiRequestHandle
import com.andsi.airlyrics.ui.navigation.Page

internal fun createNowPlayingPage(activity: MainUiHost): View = NowPlayingPageView(activity, activity.nowPlayingThemedContext())

/**
 * In-app lyrics page and the app's home screen: a slim top bar (artwork, title, overflow),
 * the scrolling lyrics stage with the provider credit as its last line, a thin progress bar
 * and one row of controls (comments, previous, play/pause, next, AI).
 *
 * The page owns a [NowPlayingSession] only while attached, so leaving the page or rebuilding
 * it releases the media-session callback and any pending lyrics lookup.
 */
@SuppressLint("ViewConstructor")
private class NowPlayingPageView(
    private val host: MainUiHost,
    private val ui: Context
) : FrameLayout(ui), NowPlayingListener {
    private val scheme: TonalScheme = host.nowPlayingScheme()
    private var session: NowPlayingSession? = null
    private var track: NowPlayingTrackUi? = null
    private var lines: List<NowPlayingLineUi> = emptyList()
    private var lastShownSecond = -1L
    private var frameScheduled = false
    private var commentCountRequest: UiRequestHandle? = null
    private var commentCountKey: String? = null

    private val artwork = ImageView(ui)
    private val title = TextView(ui)
    private val subtitle = TextView(ui)
    private val moreButton = ui.iconButton(host, R.drawable.ic_air_more_vert, R.string.ui_more_options, scheme.onSurfaceVariant)
    private val stage = LyricsStageView(ui)
    private val openMediaButton = ui.pillButton(host, scheme, host.getString(R.string.ui_open_media_sources), filled = true)
    private val progress = SlimProgressBar(ui)
    private val elapsed = TextView(ui)
    private val remaining = TextView(ui)
    private val commentsButton = ui.iconButton(host, R.drawable.ic_air_chat_bubble, R.string.ui_comments, scheme.onSurfaceVariant)
    private val commentBadge = TextView(ui)
    private val previous = ui.iconButton(host, R.drawable.ic_air_skip_previous, R.string.ui_previous_track, scheme.onSurface, iconDp = 34, boxDp = 56)
    private val next = ui.iconButton(host, R.drawable.ic_air_skip_next, R.string.ui_next_track, scheme.onSurface, iconDp = 34, boxDp = 56)
    private val playPause = ImageView(ui)
    private val aiButton = ui.iconButton(host, R.drawable.ic_air_auto_awesome, R.string.ui_ai_intro, scheme.onSurfaceVariant)

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
        setBackgroundColor(scheme.surface)
        val column = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        column.addView(createTopBar())
        column.addView(FrameLayout(ui).apply {
            addView(stage.apply {
                setTextColor(scheme.onSurface)
                setFooterColor(scheme.outline)
                showTranslation = host.nowPlayingTranslationVisible()
                onLineTapped = { line -> session?.seekToLyricLine(line.startMs) }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(openMediaButton.apply {
                visibility = GONE
                setOnClickListener { host.uiActions.selectPage(Page.MEDIA) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = host.dp(112)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
        commentCountRequest?.cancel()
        commentCountRequest = null
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
        openMediaButton.visibility = GONE
        title.text = track.title.ifBlank { host.getString(R.string.ui_unknown_song) }
        subtitle.text = listOf(track.artist, track.sourceName).filter { it.isNotBlank() }.joinToString(" · ")
        if (track.artwork != null) {
            artwork.setImageBitmap(track.artwork)
            artwork.imageTintList = null
            artwork.background = null
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setPadding(0, 0, 0, 0)
        } else {
            showPlaceholderArtwork()
        }
        host.onNowPlayingArtwork(track.artwork)
        progress.durationMs = track.durationMs
        progress.isEnabled = track.canSeek && track.durationMs > 0L
        setEnabled(previous, track.canSkipPrevious)
        setEnabled(next, track.canSkipNext)
        setEnabled(playPause, track.canPlayPause)
        setEnabled(commentsButton, track.title.isNotBlank())
        setEnabled(aiButton, track.title.isNotBlank())
        playPause.setImageResource(if (track.isPlaying) R.drawable.ic_air_pause else R.drawable.ic_air_play)
        playPause.contentDescription = host.getString(if (track.isPlaying) R.string.ui_pause else R.string.ui_play)
        lastShownSecond = -1L
        prefetchCommentCount(track)
    }

    override fun onLyricsChanged(lyrics: NowPlayingLyricsUi) {
        when (lyrics) {
            is NowPlayingLyricsUi.Loading -> {
                lines = emptyList()
                stage.showMessage(lyrics.message)
            }
            is NowPlayingLyricsUi.Message -> {
                lines = emptyList()
                stage.showMessage(lyrics.message)
            }
            is NowPlayingLyricsUi.Lines -> {
                lines = lyrics.lines
                // Provider credit is a usage requirement for some sources (e.g. Spicy Lyrics);
                // it is drawn as the last line of the lyrics.
                stage.showLines(lyrics.lines, lyrics.attribution)
            }
        }
    }

    /** Comment total for the badge; the first hot page is cached so opening the sheet is instant. */
    private fun prefetchCommentCount(track: NowPlayingTrackUi) {
        val key = track.title + '\u0000' + track.artist
        if (key == commentCountKey) return
        commentCountKey = key
        commentCountRequest?.cancel()
        commentBadge.visibility = GONE
        if (track.title.isBlank()) return
        commentCountRequest = host.loadSongComments(track, SongCommentSortUi.HOT, 0) { result ->
            commentCountRequest = null
            val total = result.getOrNull()?.total ?: 0L
            if (total > 0L && commentCountKey == key) {
                commentBadge.text = formatCount(total)
                commentBadge.visibility = VISIBLE
            }
        }
    }

    private fun showNoMedia() {
        lines = emptyList()
        commentCountKey = null
        commentCountRequest?.cancel()
        commentBadge.visibility = GONE
        title.text = host.getString(R.string.ui_no_media_detected_yet)
        subtitle.text = host.getString(R.string.ui_media_streams_empty_hint)
        showPlaceholderArtwork()
        stage.showMessage(host.getString(R.string.ui_now_playing_waiting))
        openMediaButton.visibility = VISIBLE
        progress.isEnabled = false
        progress.durationMs = 0L
        progress.setPosition(0L)
        elapsed.text = formatTime(0L)
        remaining.text = formatTime(0L)
        listOf(previous, playPause, next, commentsButton, aiButton).forEach { setEnabled(it, false) }
        playPause.setImageResource(R.drawable.ic_air_play)
    }

    private fun updateProgress(positionMs: Long) {
        val current = track ?: return
        progress.setPosition(positionMs)
        val second = positionMs / 1000L
        if (second != lastShownSecond) {
            lastShownSecond = second
            if (!progress.isDragging) elapsed.text = formatTime(positionMs)
            remaining.text = if (current.durationMs > 0L) "-" + formatTime(current.durationMs - positionMs) else ""
        }
    }

    private fun createTopBar(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(20), host.dp(12), host.dp(8), host.dp(8))
        val size = host.dp(44)
        addView(artwork.apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, host.dp(10).toFloat())
                }
            }
        })
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = host.dp(12)
                marginEnd = host.dp(4)
            }
            addView(title.apply {
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(scheme.onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(subtitle.apply {
                textSize = 13f
                setTextColor(scheme.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        moreButton.setOnClickListener { showMenu(it) }
        addView(moreButton)
    }

    private fun showMenu(anchor: View) {
        val hasTranslation = lines.any { !it.translation.isNullOrBlank() }
        showNowPlayingMenu(
            host,
            ui,
            scheme,
            anchor,
            listOf(
                listOf(
                    NowPlayingMenuItem(
                        label = host.getString(R.string.ui_show_translation),
                        checked = stage.showTranslation,
                        enabled = hasTranslation || stage.showTranslation
                    ) {
                        val visible = !stage.showTranslation
                        stage.showTranslation = visible
                        host.setNowPlayingTranslationVisible(visible)
                    },
                    NowPlayingMenuItem(host.getString(R.string.ui_lyrics_offset), enabled = track != null) {
                        showLyricsOffsetSheet(host, ui) { session?.refreshLyricsOffset() }
                    },
                    NowPlayingMenuItem(host.getString(R.string.ui_search_online_again), enabled = track != null) {
                        showConfirmSheet(
                            host,
                            ui,
                            host.getString(R.string.ui_search_online_again_confirm),
                            host.getString(R.string.ui_search_online_replace_cache_msg),
                            host.getString(R.string.ui_search_online_again)
                        ) { host.uiActions.searchOnlineLyricsForCurrentMedia() }
                    },
                    NowPlayingMenuItem(
                        label = host.getString(R.string.ui_menu_floating_lyrics),
                        checked = host.quickFloatingDesiredVisible
                    ) {
                        if (host.quickFloatingDesiredVisible) host.uiActions.hideFloatingLyrics()
                        else host.uiActions.showFloatingLyrics()
                    }
                ),
                listOf(
                    NowPlayingMenuItem(host.getString(R.string.ui_theme_colors)) { showThemeSheet(host, ui) },
                    NowPlayingMenuItem(host.getString(R.string.ui_ai_search_settings)) { showAiSettingsSheet(host, ui) }
                ),
                listOf(
                    NowPlayingMenuItem(host.getString(R.string.ui_media_sources)) { host.uiActions.selectPage(Page.MEDIA) },
                    NowPlayingMenuItem(host.getString(R.string.ui_floating_style)) { host.uiActions.selectPage(Page.FLOATING) },
                    NowPlayingMenuItem(host.getString(R.string.ui_settings)) { host.uiActions.selectPage(Page.SETTINGS) }
                )
            )
        )
    }

    private fun createProgress(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(18), host.dp(8), host.dp(18), 0)
        addView(progress.apply {
            setColors(
                track = scheme.onSurface.withAlpha(36),
                idleFill = scheme.onSurface,
                active = scheme.primary
            )
            onSeekStart = { elapsed.setTextColor(scheme.primary) }
            onSeeking = { position -> elapsed.text = formatTime(position) }
            onSeekEnd = { position ->
                elapsed.setTextColor(scheme.onSurfaceVariant)
                session?.seekToPlayback(position)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(host.dp(6), 0, host.dp(6), 0)
            addView(elapsed.apply { timeLabel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(remaining.apply {
                timeLabel()
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun TextView.timeLabel() {
        textSize = 11f
        setTextColor(scheme.onSurfaceVariant)
        fontFeatureSettings = "tnum"
    }

    private fun createControls(): View = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(24))
        commentsButton.setOnClickListener { track?.let { showCommentsSheet(host, ui, it) } }
        previous.setOnClickListener { session?.skipToPrevious() }
        playPause.setOnClickListener { session?.togglePlayPause() }
        next.setOnClickListener { session?.skipToNext() }
        aiButton.setOnClickListener {
            track?.let { current -> showAiIntroSheet(host, ui, current, lines.joinToString("\n") { it.text }) }
        }
        addView(slot(FrameLayout(ui).apply {
            addView(commentsButton, LayoutParams(host.dp(44), host.dp(44), Gravity.CENTER))
            addView(commentBadge.apply {
                visibility = GONE
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(scheme.onPrimary)
                gravity = Gravity.CENTER
                minWidth = host.dp(16)
                setPadding(host.dp(4), 0, host.dp(4), 0)
                background = roundedRect(scheme.primary, host.dp(8).toFloat())
                isClickable = false
            }, LayoutParams(LayoutParams.WRAP_CONTENT, host.dp(16), Gravity.TOP or Gravity.END))
        }, wrapWidth = host.dp(64)))
        addView(slot(previous))
        addView(slot(playPause.apply {
            setImageResource(R.drawable.ic_air_play)
            imageTintList = ColorStateList.valueOf(scheme.surface)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = host.dp(18)
            setPadding(pad, pad, pad, pad)
            background = rippleOf(
                scheme.surface,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(scheme.onSurface)
                }
            )
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(host.dp(64), host.dp(64))
        }))
        addView(slot(next))
        addView(slot(aiButton))
    }

    /** Equal-width cell so the five controls are evenly spaced. */
    private fun slot(child: View, wrapWidth: Int? = null): View = FrameLayout(ui).apply {
        val params = child.layoutParams
        val width = wrapWidth ?: params?.width ?: LayoutParams.WRAP_CONTENT
        val height = if (wrapWidth != null) host.dp(48) else params?.height ?: LayoutParams.WRAP_CONTENT
        addView(child, LayoutParams(width, height, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun showPlaceholderArtwork() {
        artwork.setImageResource(R.drawable.ic_air_music_note)
        artwork.imageTintList = ColorStateList.valueOf(scheme.onSecondaryContainer)
        artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
        artwork.background = GradientDrawable().apply { setColor(scheme.secondaryContainer) }
        val inset = host.dp(11)
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
    }
}
