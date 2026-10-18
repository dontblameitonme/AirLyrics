package com.andsi.airlyrics.ui.pages.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.ui.model.NowPlayingLineUi
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Scrolling lyrics drawn directly on a canvas.
 *
 * The active line is kept around the upper third of the view, neighbours fade out with
 * distance, and the scroll follows the active line with frame-rate independent easing.
 * Dragging lets the user browse; auto-follow resumes shortly after the finger lifts.
 * Tapping a line reports its start time so the host can seek the player.
 */
internal class LyricsStageView(context: Context) : View(context) {
    var onLineTapped: ((NowPlayingLineUi) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val linePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 24f * scaledDensity
    }
    private val translationPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * scaledDensity
    }
    private val messagePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f * scaledDensity
    }
    private val horizontalPadding = 28f * density
    private val lineGap = 22f * density
    private val translationGap = 4f * density

    private var lines: List<NowPlayingLineUi> = emptyList()
    private var message: String? = null
    private var laidOut: List<LaidOutLine> = emptyList()
    private var messageLayout: StaticLayout? = null
    private var textColor = 0xFF000000.toInt()

    private var activeIndex = -1
    private var scrollY = 0f
    private var lastFrameNanos = 0L
    private var userScrolling = false
    private var resumeFollowAtMs = 0L

    private class LaidOutLine(
        val line: NowPlayingLineUi,
        val text: StaticLayout,
        val translation: StaticLayout?,
        val top: Float,
        val height: Float
    )

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            userScrolling = true
            parent?.requestDisallowInterceptTouchEvent(true)
            scrollY = (scrollY + distanceY).coerceIn(minScroll(), maxScroll())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val y = e.y + scrollY
            laidOut.firstOrNull { y >= it.top - lineGap / 2 && y <= it.top + it.height + lineGap / 2 }
                ?.let { onLineTapped?.invoke(it.line) }
            return true
        }
    })

    fun setTextColor(color: Int) {
        textColor = color
        invalidate()
    }

    var showTranslation: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            relayout()
            if (activeIndex >= 0) scrollY = targetScrollFor(activeIndex)
            invalidate()
        }

    fun showLines(newLines: List<NowPlayingLineUi>) {
        if (newLines == lines && message == null) return
        lines = newLines
        message = null
        activeIndex = -1
        relayout()
        scrollY = targetScrollFor(0)
        invalidate()
    }

    fun showMessage(text: String) {
        if (message == text) return
        message = text
        lines = emptyList()
        laidOut = emptyList()
        activeIndex = -1
        relayout()
        invalidate()
    }

    /** Called once per frame with the current lyrics-time position. */
    fun updatePosition(lyricsPositionMs: Long) {
        if (laidOut.isEmpty()) return
        val index = activeLineIndex(lines, lyricsPositionMs)
        if (index != activeIndex) {
            activeIndex = index
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        scrollY = targetScrollFor(max(activeIndex, 0))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (userScrolling) {
                userScrolling = false
                resumeFollowAtMs = System.currentTimeMillis() + RESUME_FOLLOW_DELAY_MS
            }
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        messageLayout?.let { layout ->
            messagePaint.color = ColorUtils.setAlphaComponent(textColor, 170)
            canvas.save()
            canvas.translate(horizontalPadding, (height - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
            return
        }
        if (laidOut.isEmpty()) return

        advanceScroll()
        val activeCenter = laidOut.getOrNull(activeIndex)?.let { it.top + it.height / 2f }
        for ((index, item) in laidOut.withIndex()) {
            val top = item.top - scrollY
            if (top + item.height < -lineGap || top > height + lineGap) continue

            val isActive = index == activeIndex
            val distance = activeCenter?.let { abs((item.top + item.height / 2f) - it) / max(height, 1) } ?: 0.5f
            val alpha = if (isActive) 255 else (150 - distance * 160).toInt().coerceIn(40, 120)
            val scale = if (isActive) 1f else INACTIVE_SCALE

            canvas.save()
            canvas.translate(horizontalPadding, top)
            canvas.scale(scale, scale, 0f, item.height / 2f)
            linePaint.color = ColorUtils.setAlphaComponent(textColor, alpha)
            item.text.draw(canvas)
            item.translation?.let { translation ->
                canvas.translate(0f, item.text.height + translationGap)
                translationPaint.color = ColorUtils.setAlphaComponent(textColor, (alpha * 0.8f).toInt())
                translation.draw(canvas)
            }
            canvas.restore()
        }
        if (isFollowing() && abs(scrollY - targetScrollFor(max(activeIndex, 0))) > 0.5f) {
            postInvalidateOnAnimation()
        }
    }

    private fun advanceScroll() {
        val now = System.nanoTime()
        val dtSeconds = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        lastFrameNanos = now
        if (!isFollowing()) return
        val target = targetScrollFor(max(activeIndex, 0))
        // Critically damped-looking ease: ~90% of the distance in about 0.3s at any frame rate.
        val factor = 1f - exp(-SCROLL_EASE_PER_SECOND * dtSeconds)
        scrollY += (target - scrollY) * factor
    }

    private fun isFollowing(): Boolean = !userScrolling && System.currentTimeMillis() >= resumeFollowAtMs

    private fun targetScrollFor(index: Int): Float {
        val item = laidOut.getOrNull(index) ?: return minScroll()
        return item.top + item.height / 2f - height * FOCUS_FRACTION
    }

    private fun minScroll(): Float = -height * FOCUS_FRACTION

    private fun maxScroll(): Float = laidOut.lastOrNull()?.let { it.top + it.height - height * FOCUS_FRACTION } ?: 0f

    private fun relayout() {
        val width = (width - horizontalPadding * 2).toInt()
        if (width <= 0) {
            laidOut = emptyList()
            messageLayout = null
            return
        }
        messageLayout = message?.let { staticLayout(it, messagePaint, width, Layout.Alignment.ALIGN_CENTER) }
        var top = 0f
        laidOut = lines.map { line ->
            val text = staticLayout(line.text.ifBlank { "♪" }, linePaint, width, Layout.Alignment.ALIGN_NORMAL)
            val translation = line.translation?.takeIf { showTranslation && it.isNotBlank() }
                ?.let { staticLayout(it, translationPaint, width, Layout.Alignment.ALIGN_NORMAL) }
            val height = text.height + (translation?.let { it.height + translationGap } ?: 0f)
            LaidOutLine(line, text, translation, top, height).also {
                top += height + lineGap
            }
        }
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()

    companion object {
        private const val FOCUS_FRACTION = 0.36f
        private const val INACTIVE_SCALE = 0.94f
        private const val SCROLL_EASE_PER_SECOND = 7.5f
        private const val RESUME_FOLLOW_DELAY_MS = 2_500L

        /** Index of the last line that has started, or -1 before the first line. */
        internal fun activeLineIndex(lines: List<NowPlayingLineUi>, positionMs: Long): Int {
            var low = 0
            var high = lines.lastIndex
            var result = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].startMs <= positionMs) {
                    result = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return result
        }
    }
}
