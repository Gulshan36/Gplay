package com.example.iinadroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Round progress ring with the percentage in the middle. [percent] < 0 shows a spinning arc instead. */
class BufferRing(context: Context) : View(context) {
    var percent = -1
        set(v) { field = v.coerceIn(-1, 100); invalidate() }

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0x33FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 20 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private var spin = 0f

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((84 * d).toInt(), (84 * d).toInt())

    override fun onDraw(c: Canvas) {
        val pad = 6 * d
        rect.set(pad, pad, width - pad, height - pad)
        c.drawOval(rect, track)
        arc.color = Ui.ACCENT_HI
        if (percent < 0) {
            spin = (spin + 7f) % 360f
            c.drawArc(rect, spin, 100f, false, arc)
            postInvalidateOnAnimation()
        } else {
            c.drawArc(rect, -90f, percent * 3.6f, false, arc)
            c.drawText("$percent%", width / 2f, height / 2f + 7 * d, text)
        }
    }
}
