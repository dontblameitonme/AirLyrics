package com.andsi.airlyrics.ui.pages.nowplaying

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Thin seek bar: a 4dp track whose fill turns into the primary color with a 12dp thumb only
 * while the finger is down. The touch target stays tall even though the track is thin.
 */
internal class SlimProgressBar(context: Context) : View(context) {
    var onSeekStart: (() -> Unit)? = null
    var onSeeking: ((Long) -> Unit)? = null
    var onSeekEnd: ((Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackHeight = 4f * density
    private val thumbRadius = 6f * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var idleFillColor = 0xFF000000.toInt()
    private var activeColor = 0xFF000000.toInt()

    var durationMs: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            invalidate()
        }

    private var positionMs: Long = 0L
    private var dragging = false
    val isDragging: Boolean get() = dragging

    fun setColors(track: Int, idleFill: Int, active: Int) {
        trackPaint.color = track
        idleFillColor = idleFill
        activeColor = active
        thumbPaint.color = active
        invalidate()
    }

    fun setPosition(position: Long) {
        if (dragging) return
        positionMs = position.coerceIn(0L, durationMs.coerceAtLeast(0L))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (24f * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        val cy = height / 2f
        val half = trackHeight / 2f
        rect.set(left, cy - half, right, cy + half)
        canvas.drawRoundRect(rect, half, half, trackPaint)
        val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val x = left + (right - left) * fraction
        if (fraction > 0f) {
            fillPaint.color = if (dragging) activeColor else idleFillColor
            rect.set(left, cy - half, x, cy + half)
            canvas.drawRoundRect(rect, half, half, fillPaint)
        }
        if (dragging) canvas.drawCircle(x, cy, thumbRadius, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeekStart?.invoke()
                updateFromTouch(event.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) updateFromTouch(event.x)
            MotionEvent.ACTION_UP -> if (dragging) {
                updateFromTouch(event.x)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onSeekEnd?.invoke(positionMs)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onSeekEnd?.invoke(positionMs)
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float) {
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        val fraction = ((x - left) / (right - left).coerceAtLeast(1f)).coerceIn(0f, 1f)
        positionMs = (durationMs * fraction).toLong()
        invalidate()
        onSeeking?.invoke(positionMs)
    }
}
