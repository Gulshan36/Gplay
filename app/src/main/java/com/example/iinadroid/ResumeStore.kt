package com.example.iinadroid

import android.content.Context

/** Remembers where each video was left off ("pos|duration" in seconds). */
object ResumeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun key(v: Video) = v.uri.toString()

    fun save(ctx: Context, v: Video, pos: Double, dur: Double) {
        if (dur <= 0) return
        prefs(ctx).edit().putString(key(v), "$pos|$dur").apply()
    }

    fun clear(ctx: Context, v: Video) = prefs(ctx).edit().remove(key(v)).apply()

    /** Returns (position, duration) or null. */
    fun get(ctx: Context, v: Video): Pair<Double, Double>? {
        val s = prefs(ctx).getString(key(v), null) ?: return null
        val p = s.split("|")
        return try { p[0].toDouble() to p[1].toDouble() } catch (_: Exception) { null }
    }

    /** Position to continue from, or 0 when there is nothing worth resuming. */
    fun resumeAt(ctx: Context, v: Video): Double {
        val (pos, dur) = get(ctx, v) ?: return 0.0
        return if (pos > 5 && dur - pos > 10) pos else 0.0
    }

    fun progress(ctx: Context, v: Video): Float {
        val (pos, dur) = get(ctx, v) ?: return 0f
        return if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** Drops saved positions of videos that are no longer on the device (network streams are never stored here). */
    fun prune(ctx: Context, existing: Collection<Video>) {
        val keep = existing.mapTo(HashSet()) { key(it) }
        val p = prefs(ctx)
        val stale = p.all.keys.filter { it.startsWith("content://media/") && it !in keep }
        if (stale.isNotEmpty()) p.edit().apply { stale.forEach { remove(it) } }.apply()
    }
}
