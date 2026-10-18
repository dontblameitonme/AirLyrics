package com.andsi.airlyrics.ui.pages.nowplaying

import android.annotation.SuppressLint
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.NowPlayingListener
import com.andsi.airlyrics.ui.model.NowPlayingLyricsUi
import com.andsi.airlyrics.ui.model.NowPlayingSession
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun createNowPlayingPage(activity: MainUiHost): View = NowPlayingPageView(activity)

/**
 * In-app lyrics page: track header, scrolling lyrics, progress and transport controls.
 *
 * The page owns a [NowPlayingSession] only while attached, so leaving the tab or rebuilding
 * the page releases the media-session callback and any pending lyrics lookup.
 */
@SuppressLint("ViewConstructor")
private class NowPlayingPageView(private val host: MainUiHost) : FrameLayout(host), NowPlayingListener {
    private var session: NowPlayingSession? = null
    private var track: NowPlayingTrackUi? = null
    private var userSeeking = false
    private var lastShownSecond = -1L
    private var frameScheduled = false

    private val artwork = ImageView(host)
    private val title = TextView(host)
    private val subtitle = TextView(host)
    private val stage = LyricsStageView(host)
    private val attribution = TextView(host)
    private val seekBar = SeekBar(host)
    private val elapsed = TextView(host)
    private val remaining = TextView(host)
    private val previous = controlButton(R.drawable.ic_air_skip_previous, R.string.ui_previous_track, 30)
    private val playPause = controlButton(R.drawable.ic_air_pause, R.string.ui_pause, 34)
    private val next = controlButton(R.drawable.ic_air_skip_next, R.string.ui_next_track, 30)

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
        val column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        column.addView(createHeader())
        column.addView(stage.apply {
            setTextColor(host.colorTextStrong)
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
        seekBar.max = track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        seekBar.isEnabled = track.canSeek && track.durationMs > 0L
        setEnabled(previous, track.canSkipPrevious)
        setEnabled(next, track.canSkipNext)
        setEnabled(playPause, track.canPlayPause)
        playPause.setImageResource(if (track.isPlaying) R.drawable.ic_air_pause else R.drawable.ic_air_play)
        playPause.contentDescription = host.getString(if (track.isPlaying) R.string.ui_pause else R.string.ui_play)
        lastShownSecond = -1L
    }

    override fun onLyricsChanged(lyrics: NowPlayingLyricsUi) {
        when (lyrics) {
            is NowPlayingLyricsUi.Loading -> {
                stage.showMessage(lyrics.message)
                attribution.visibility = GONE
            }
            is NowPlayingLyricsUi.Message -> {
                stage.showMessage(lyrics.message)
                attribution.visibility = GONE
            }
            is NowPlayingLyricsUi.Lines -> {
                stage.showLines(lyrics.lines)
                // Provider credit is a usage requirement for some sources (e.g. Spicy Lyrics).
                attribution.text = lyrics.attribution
                attribution.visibility = if (lyrics.attribution.isNullOrBlank()) GONE else VISIBLE
            }
        }
    }

    private fun showNoMedia() {
        title.text = host.getString(R.string.ui_no_media_detected_yet)
        subtitle.text = host.getString(R.string.ui_media_streams_empty_hint)
        showPlaceholderArtwork()
        stage.showMessage(host.getString(R.string.ui_now_playing_waiting))
        attribution.visibility = GONE
        seekBar.isEnabled = false
        seekBar.progress = 0
        elapsed.text = formatTime(0L)
        remaining.text = formatTime(0L)
        listOf(previous, playPause, next).forEach { setEnabled(it, false) }
        playPause.setImageResource(R.drawable.ic_air_play)
    }

    private fun updateProgress(positionMs: Long) {
        val current = track ?: return
        if (!userSeeking) seekBar.progress = positionMs.coerceIn(0L, seekBar.max.toLong()).toInt()
        val second = positionMs / 1000L
        if (second != lastShownSecond) {
            lastShownSecond = second
            elapsed.text = formatTime(positionMs)
            remaining.text = if (current.durationMs > 0L) "-" + formatTime(current.durationMs - positionMs) else ""
        }
    }

    private fun createHeader(): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(20), host.dp(16), host.dp(20), host.dp(12))
        val size = host.dp(56)
        addView(artwork.apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, host.dp(12).toFloat())
                }
            }
        })
        addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = host.dp(14)
            }
            addView(title.apply {
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
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
    }

    private fun createProgress(): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(12), host.dp(4), host.dp(12), 0)
        addView(seekBar.apply {
            progressTintList = ColorStateList.valueOf(host.colorAccent)
            thumbTintList = ColorStateList.valueOf(host.colorAccent)
            progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorTextMuted, 90))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = formatTime(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    userSeeking = false
                    session?.seekToPlayback(bar.progress.toLong())
                }
            })
        })
        addView(LinearLayout(host).apply {
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
        textSize = 11f
        setTextColor(host.colorTextMuted)
        fontFeatureSettings = "tnum"
    }

    private fun createControls(): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, host.dp(6), 0, host.dp(14))
        previous.setOnClickListener { session?.skipToPrevious() }
        playPause.setOnClickListener { session?.togglePlayPause() }
        next.setOnClickListener { session?.skipToNext() }
        addView(previous)
        addView(playPause.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(host.colorAccentSoft)
            }
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = host.dp(28)
                marginEnd = host.dp(28)
            }
        })
        addView(next)
    }

    private fun controlButton(@DrawableRes icon: Int, label: Int, iconDp: Int): ImageView = ImageView(host).apply {
        val box = host.dp(if (iconDp >= 34) 64 else 52)
        layoutParams = LinearLayout.LayoutParams(box, box)
        val inset = (box - host.dp(iconDp)) / 2
        setPadding(inset, inset, inset, inset)
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(host.colorTextStrong)
        contentDescription = host.getString(label)
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback()
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
        view.alpha = if (enabled) 1f else 0.35f
    }

    private companion object {
        const val PAUSED_TICK_MS = 250L
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
