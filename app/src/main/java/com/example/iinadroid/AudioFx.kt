package com.example.iinadroid

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Equalizer, bass boost and virtualizer using Android's own audio effects, attached to the audio session
 * of the player's output. (The mpv build bundled with the app has no audio filters, so it cannot do this itself.)
 */
class AudioFx(private val prefs: SharedPreferences, private val session: Int) {
    private var eq: Equalizer? = null
    private var bassFx: BassBoost? = null
    private var virtFx: Virtualizer? = null

    var bandCount = 5
        private set
    var freqNames: List<String> = listOf("60", "230", "910", "3.6k", "14k")
        private set
    /** Largest boost / cut the device allows, in tenths of a dB. */
    var limit = 120
        private set
    var eqSupported = false
        private set
    var bassSupported = false
        private set
    var virtSupported = false
        private set

    var enabled = prefs.getBoolean("eq_on", false)
    var bands = IntArray(12) { prefs.getInt("eq_b$it", 0) }   // tenths of a dB
    var bass = prefs.getInt("eq_bass", 0)                     // 0..100
    var virtualizer = prefs.getInt("eq_virt", 0)              // 0..100
    var preset = prefs.getInt("eq_preset", 0)                 // index into presets, -1 = custom

    private val knots: List<Pair<String, IntArray>> = listOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Bass" to intArrayOf(70, 40, 0, 0, 0),
        "Vocal" to intArrayOf(-20, 0, 40, 30, -10),
        "Treble" to intArrayOf(0, 0, 0, 40, 70),
        "Rock" to intArrayOf(50, 30, -10, 30, 50),
        "Cinema" to intArrayOf(40, 20, 0, 30, 40),
    )
    val presetNames get() = knots.map { it.first }

    init {
        try {
            val e = Equalizer(0, session)
            eq = e
            bandCount = e.numberOfBands.toInt().coerceIn(1, 12)
            freqNames = (0 until bandCount).map { b ->
                val hz = e.getCenterFreq(b.toShort()) / 1000
                if (hz >= 1000) "%.1fk".format(java.util.Locale.US, hz / 1000.0).replace(".0k", "k") else "$hz"
            }
            limit = minOf(e.bandLevelRange[1] / 10, 150)
            eqSupported = true
        } catch (_: Throwable) { }
        try { bassFx = BassBoost(0, session); bassSupported = bassFx?.strengthSupported == true } catch (_: Throwable) { }
        try { virtFx = Virtualizer(0, session); virtSupported = virtFx?.strengthSupported == true } catch (_: Throwable) { }
        apply()
    }

    fun save() {
        prefs.edit().putBoolean("eq_on", enabled).putInt("eq_bass", bass).putInt("eq_virt", virtualizer).putInt("eq_preset", preset)
            .apply { bands.forEachIndexed { i, v -> putInt("eq_b$i", v) } }.apply()
    }

    /** Spread the 5-point preset curve over however many bands this device has. */
    fun applyPreset(i: Int) {
        preset = i
        val k = knots[i].second
        for (b in 0 until bandCount) {
            val pos = if (bandCount == 1) 0.0 else b * 4.0 / (bandCount - 1)
            val lo = pos.toInt().coerceAtMost(3)
            val t = pos - lo
            bands[b] = (k[lo] * (1 - t) + k[lo + 1] * t).roundToInt().coerceIn(-limit, limit)
        }
        save()
    }

    fun reset() { bands.fill(0); bass = 0; virtualizer = 0; preset = 0; save(); apply() }

    /** Push the current values to the audio effects (or switch them off). */
    fun apply() {
        try {
            eq?.let { e ->
                val r = e.bandLevelRange
                for (b in 0 until bandCount) e.setBandLevel(b.toShort(), (bands[b] * 10).coerceIn(r[0].toInt(), r[1].toInt()).toShort())
                e.enabled = enabled
            }
            bassFx?.let { if (bassSupported) { it.setStrength((bass * 10).toShort()); it.enabled = enabled && bass > 0 } }
            virtFx?.let { if (virtSupported) { it.setStrength((virtualizer * 10).toShort()); it.enabled = enabled && virtualizer > 0 } }
        } catch (_: Throwable) { }
    }

    fun release() {
        try { eq?.release() } catch (_: Throwable) { }
        try { bassFx?.release() } catch (_: Throwable) { }
        try { virtFx?.release() } catch (_: Throwable) { }
        eq = null; bassFx = null; virtFx = null
    }
}

/** Vertical equalizer band: drag the knob up or down; value in tenths of a dB within +-[limit]. */
class EqBand(context: Context, private val limit: Int) : View(context) {
    var value = 0
        set(v) { field = v.coerceIn(-limit, limit); invalidate() }
    var onChange: ((Int, Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = 1.5f * d }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize((40 * d).toInt(), w), (156 * d).toInt())

    private fun yFor(v: Int): Float {
        val top = 12 * d; val bottom = height - 12 * d
        return bottom - (v + limit) / (2f * limit) * (bottom - top)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        rect.set(cx - 2.5f * d, yFor(limit), cx + 2.5f * d, yFor(-limit))
        c.drawRoundRect(rect, 3 * d, 3 * d, track)
        fill.color = Ui.ACCENT
        val y0 = yFor(0); val y = yFor(value)
        rect.set(cx - 2.5f * d, minOf(y0, y), cx + 2.5f * d, maxOf(y0, y))
        c.drawRoundRect(rect, 3 * d, 3 * d, fill)
        c.drawLine(cx - 10 * d, y0, cx + 10 * d, y0, mid)
        knob.color = Ui.ACCENT_HI
        c.drawCircle(cx, y, 9 * d, knob)
        knob.color = 0xFF0B0D14.toInt()
        c.drawCircle(cx, y, 3.5f * d, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        fun set(y: Float, final: Boolean) {
            val top = 12 * d; val bottom = height - 12 * d
            val raw = ((bottom - y) / (bottom - top)) * 2f * limit - limit
            var v = (raw / 5f).roundToInt() * 5          // 0.5 dB steps
            if (abs(v) <= 4) v = 0                       // snap to flat
            value = v
            onChange?.invoke(value, final)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); set(e.y, false) }
            MotionEvent.ACTION_MOVE -> set(e.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(e.y, true)
        }
        return true
    }
}
