package com.example.iinadroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Thin IINA-style slider. [barKnob] = vertical bar knob (seek), otherwise round knob (volume). */
class Slider(context: Context, private val barKnob: Boolean) : View(context) {

    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    var onTouchEnd: ((Float) -> Unit)? = null
    /** Fraction (0..1) of the file already downloaded, drawn as a lighter bar ahead of the progress. */
    var buffered = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var dragging = false
        private set

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val rect = RectF()

    /** Use the theme colours instead of plain white. */
    fun tint(fillColor: Int, knobColor: Int) { fill.color = fillColor; knob.color = knobColor; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val wantW = (96 * d).toInt()
        setMeasuredDimension(resolveSize(wantW, w), (30 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pad = 8 * d
        val l = pad
        val r = width - pad
        val cy = height / 2f
        val th = 3 * d
        rect.set(l, cy - th / 2, r, cy + th / 2)
        c.drawRoundRect(rect, th, th, track)
        if (buffered > 0f) {
            rect.set(l, cy - th / 2, l + (r - l) * buffered, cy + th / 2)
            c.drawRoundRect(rect, th, th, bufPaint)
        }
        val x = l + (r - l) * progress
        rect.set(l, cy - th / 2, x, cy + th / 2)
        c.drawRoundRect(rect, th, th, fill)
        if (barKnob) {
            rect.set(x - 2 * d, cy - 8 * d, x + 2 * d, cy + 8 * d)
            c.drawRoundRect(rect, 2 * d, 2 * d, knob)
        } else {
            c.drawCircle(x, cy, 7.5f * d, knob)
        }
    }

    private fun valueAt(x: Float): Float {
        val pad = 8 * d
        return ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                dragging = true
                onTouchStart?.invoke()
                progress = valueAt(e.x); onChange?.invoke(progress, true)
            }
            MotionEvent.ACTION_MOVE -> { progress = valueAt(e.x); onChange?.invoke(progress, true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                progress = valueAt(e.x)
                onTouchEnd?.invoke(progress)
            }
        }
        return true
    }
}
