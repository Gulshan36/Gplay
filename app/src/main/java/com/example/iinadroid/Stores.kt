package com.example.iinadroid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Saved network stream URLs (shown in the Network tab). */
object NetworkStore {
    data class Stream(val url: String, val title: String, val addedAt: Long, val lastPlayed: Long = 0)

    private fun prefs(c: Context) = c.getSharedPreferences("streams", Context.MODE_PRIVATE)

    fun all(c: Context): List<Stream> {
        val out = ArrayList<Stream>()
        try {
            val arr = JSONArray(prefs(c).getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Stream(o.getString("url"), o.optString("title"), o.optLong("addedAt"), o.optLong("lastPlayed"))
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.addedAt }
    }

    private fun save(c: Context, list: List<Stream>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title).put("addedAt", it.addedAt).put("lastPlayed", it.lastPlayed)) }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun defaultTitle(url: String): String {
        val u = Uri.parse(url)
        val last = u.lastPathSegment?.takeIf { it.isNotBlank() }
        return last ?: u.host ?: url
    }

    /** Adds the stream, or moves an existing one to the top. */
    fun add(c: Context, url: String, title: String? = null) {
        val list = all(c).toMutableList()
        val old = list.firstOrNull { it.url == url }
        list.removeAll { it.url == url }
        list += Stream(url, title?.takeIf { it.isNotBlank() } ?: old?.title ?: defaultTitle(url), System.currentTimeMillis(), old?.lastPlayed ?: 0)
        save(c, list)
    }

    /** Remember when this stream was last opened. */
    fun markPlayed(c: Context, url: String) =
        save(c, all(c).map { if (it.url == url) it.copy(lastPlayed = System.currentTimeMillis()) else it })

    fun remove(c: Context, url: String) = save(c, all(c).filter { it.url != url })

    fun rename(c: Context, url: String, title: String) =
        save(c, all(c).map { if (it.url == url) it.copy(title = title) else it })
}

/** Remembers which library videos the user has already opened, so new ones can be flagged NEW. */
object SeenStore {
    private var cache: MutableSet<String>? = null
    private fun prefs(c: Context) = c.getSharedPreferences("seen", Context.MODE_PRIVATE)

    private fun set(c: Context): MutableSet<String> =
        cache ?: HashSet(prefs(c).getStringSet("ids", emptySet()) ?: emptySet()).also { cache = it }

    /** First ever run: everything already on the device counts as seen. */
    @Synchronized
    fun ensureBaseline(c: Context, ids: List<Long>) {
        if (prefs(c).getBoolean("baseline", false)) return
        val s = set(c)
        ids.forEach { s += it.toString() }
        prefs(c).edit().putStringSet("ids", HashSet(s)).putBoolean("baseline", true).apply()
    }

    @Synchronized
    fun isNew(c: Context, id: Long) = !set(c).contains(id.toString())

    @Synchronized
    fun markSeen(c: Context, id: Long) {
        val s = set(c)
        if (s.add(id.toString())) prefs(c).edit().putStringSet("ids", HashSet(s)).apply()
    }
}
