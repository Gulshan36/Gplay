package com.example.iinadroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A ruler you drag sideways under a fixed centre marker to pick a playback speed (0.1x - 4x, 0.05 steps). */
class SpeedRuler(context: Context, private val min: Double, private val max: Double) : View(context) {

    var value = 1.0
        set(v) { field = v.coerceIn(min, max); invalidate() }
    var onChange: ((Double) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val step = 0.05
    private val gap = 9 * d
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 1.6f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED; textSize = 11 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEAL }
    private val rect = RectF()
    private var downX = 0f
    private var downValue = 1.0

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize((200 * d).toInt(), w), (62 * d).toInt())

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val base = height - 14 * d
        val span = cx / gap * step
        var i = ceil((value - span) / step - 0.001).toInt()
        val end = floor((value + span) / step + 0.001).toInt()
        while (i <= end) {
            val v = i * step
            if (v >= min - 0.0001 && v <= max + 0.0001) {
                val x = cx + ((v - value) / step).toFloat() * gap
                val whole = abs(v - v.roundToInt()) < 0.001
                val quarter = abs(v * 4 - (v * 4).roundToInt()) < 0.001
                val h = if (whole) 24 * d else if (quarter) 16 * d else 9 * d
                val fade = (1f - abs(x - cx) / cx * 0.75f).coerceIn(0.15f, 1f)
                tick.color = (if (whole) 0xFFFFFFFF else 0xFF8A90A6).toInt()
                tick.alpha = (255 * fade).toInt()
                c.drawLine(x, base, x, base - h, tick)
                if (whole && v.roundToInt() > 0) {
                    label.alpha = (255 * fade).toInt()
                    c.drawText("${v.roundToInt()}x", x, base + 13 * d, label)
                }
            }
            i++
        }
        rect.set(cx - 1.5f * d, 2 * d, cx + 1.5f * d, base + 2 * d)
        c.drawRoundRect(rect, 2 * d, 2 * d, marker)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downValue = value
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = downValue - (e.x - downX) / gap * step
                val snapped = ((raw / step).roundToInt() * step).coerceIn(min, max)
                val rounded = Math.round(snapped * 100) / 100.0
                if (abs(rounded - value) > 0.0001) {
                    value = rounded
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onChange?.invoke(rounded)
                }
            }
        }
        return true
    }
}
