package com.example.iinadroid

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/** Gplay "Nebula" look: deep ink background, layered surfaces and one switchable accent colour. */
object Ui {
    private class Theme(
        val name: String, val base: Int, val hi: Int, val lo: Int, val tone: Int,
        val folder1: Int, val folder2: Int, val splash: Int, val alias: String
    )

    private val themes = listOf(
        Theme("Violet", 0xFF8E7CFF.toInt(), 0xFFA796FF.toInt(), 0xFF6A56F2.toInt(), 0xFFC3B9FF.toInt(), 0xFF3B2F8F.toInt(), 0xFF1A1440.toInt(), 0xFF1F1750.toInt(), "IconViolet"),
        Theme("Teal", 0xFF2FC4B2.toInt(), 0xFF5EE0D0.toInt(), 0xFF1A9C8E.toInt(), 0xFF9AF0E6.toInt(), 0xFF0E6B62.toInt(), 0xFF07302C.toInt(), 0xFF0B3A36.toInt(), "IconTeal"),
        Theme("Rose", 0xFFF0648C.toInt(), 0xFFFF8AA9.toInt(), 0xFFD43D6E.toInt(), 0xFFFFC0D1.toInt(), 0xFF8A2F5F.toInt(), 0xFF34102A.toInt(), 0xFF3A1030.toInt(), "IconRose"),
        Theme("Green", 0xFF4CC47A.toInt(), 0xFF76DB9C.toInt(), 0xFF2E9E5A.toInt(), 0xFFB4F0CB.toInt(), 0xFF2F7A57.toInt(), 0xFF0E2D20.toInt(), 0xFF0F3322.toInt(), "IconGreen"),
        Theme("Amber", 0xFFF2A83B.toInt(), 0xFFFFC266.toInt(), 0xFFD4861A.toInt(), 0xFFFFDDA0.toInt(), 0xFF9A6420.toInt(), 0xFF33200A.toInt(), 0xFF3A2508.toInt(), "IconAmber"),
    )

    val count get() = themes.size
    fun name(i: Int) = themes[i].name
    fun swatch(i: Int) = themes[i].hi to themes[i].lo

    var index = 0
        private set

    fun load(c: Context) {
        index = c.getSharedPreferences("player", Context.MODE_PRIVATE).getInt("theme", 0).coerceIn(0, themes.size - 1)
    }

    /** Saves the theme and switches the launcher icon to the matching colour. */
    fun select(c: Context, i: Int) {
        index = i.coerceIn(0, themes.size - 1)
        c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putInt("theme", index).apply()
        try {
            themes.forEachIndexed { n, t ->
                c.packageManager.setComponentEnabledSetting(
                    ComponentName(c.packageName, "com.example.iinadroid." + t.alias),
                    if (n == index) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (_: Exception) { }
    }

    val BG = 0xFF0B0D14.toInt()
    val SURFACE = 0xFF161A28.toInt()
    val SURFACE2 = 0xFF222839.toInt()
    val TEXT = 0xFFF3F4FA.toInt()
    val MUTED = 0xFF8A90A6.toInt()
    val LINE = 0x14FFFFFF
    val PANEL = 0xF20E1018.toInt()

    val ACCENT: Int get() = themes[index].base
    val ACCENT_HI: Int get() = themes[index].hi
    val ACCENT_LO: Int get() = themes[index].lo
    /** Light tone of the accent, used for values and small highlights. */
    val TEAL: Int get() = themes[index].tone
    val FOLDER_1: Int get() = themes[index].folder1
    val FOLDER_2: Int get() = themes[index].folder2
    val SPLASH_END: Int get() = themes[index].splash

    /** Short format tag shown on a saved stream, taken from its link. */
    fun streamTag(url: String): String {
        val u = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            u.startsWith("rtmp") -> "RTMP"
            u.startsWith("rtsp") -> "RTSP"
            u.endsWith(".m3u8") || u.contains(".m3u8") -> "HLS"
            u.endsWith(".mpd") -> "DASH"
            else -> u.substringAfterLast('.', "").takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
                ?.uppercase() ?: "LINK"
        }
    }

    fun alpha(color: Int, a: Int) = (color and 0xFFFFFF) or (a shl 24)
    fun soft(a: Int = 0x2E) = alpha(ACCENT, a)

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float) = v * c.resources.displayMetrics.density

    fun rect(c: Context, color: Int, radiusDp: Int) =
        GradientDrawable().apply { cornerRadius = dpf(c, radiusDp.toFloat()); setColor(color) }

    /** Gradient used for the selected chip, primary buttons and the active tab. */
    fun accentFill(c: Context, radiusDp: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT_HI, ACCENT_LO))
            .apply { cornerRadius = dpf(c, radiusDp.toFloat()) }

    fun topRounded(c: Context, color: Int, radiusDp: Int): GradientDrawable {
        val r = dpf(c, radiusDp.toFloat())
        return GradientDrawable().apply { setColor(color); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
    }

    fun leftRounded(c: Context, color: Int, radiusDp: Int): GradientDrawable {
        val r = dpf(c, radiusDp.toFloat())
        return GradientDrawable().apply { setColor(color); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) }
    }

    /** The app logo in the current accent colour. */
    fun logo(c: Context): Drawable {
        val bg = c.getDrawable(R.drawable.ic_logo_bg)!!.mutate().apply { setTint(ACCENT) }
        return LayerDrawable(arrayOf(bg, c.getDrawable(R.drawable.ic_logo_mark)))
    }
}
