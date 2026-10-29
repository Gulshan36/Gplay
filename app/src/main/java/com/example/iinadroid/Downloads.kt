package com.example.iinadroid

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a network link as a file in Movies/Gplay using Android's DownloadManager, so it keeps downloading in the
 * background and shows up in the library under the "Gplay" folder.
 */
object Downloads {
    class Info(val id: Long, val status: Int, val bytes: Long, val total: Long) {
        val percent get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
        val active get() = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
        val done get() = status == DownloadManager.STATUS_SUCCESSFUL
        val failed get() = status == DownloadManager.STATUS_FAILED
    }

    private const val FOLDER = "Gplay"
    private val main = Handler(Looper.getMainLooper())
    private val known = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "m4v", "flv", "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")

    private fun prefs(c: Context) = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private fun dm(c: Context) = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun info(c: Context, url: String): Info? {
        val id = prefs(c).getLong(url, -1L)
        if (id < 0) return null
        val q = DownloadManager.Query().setFilterById(id)
        try {
            dm(c).query(q).use { cur ->
                if (!cur.moveToFirst()) { prefs(c).edit().remove(url).apply(); return null }
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (status == DownloadManager.STATUS_FAILED) {
                    android.util.Log.e("GplayDl", "failed, reason code=" + cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // the file was deleted somewhere else (for example from the Gplay folder): forget the download
                    val local = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val path = local?.let { Uri.parse(it).path }
                    if (path != null && !File(path).exists()) { prefs(c).edit().remove(url).apply(); return null }
                }
                return Info(id, status, bytes, total)
            }
        } catch (_: Exception) { return null }
    }

    fun anyActive(c: Context): Boolean = prefs(c).all.keys.any { info(c, it)?.active == true }

    /** Stops a running download or deletes a finished one (together with its file). */
    fun remove(c: Context, url: String) {
        val id = prefs(c).getLong(url, -1L)
        if (id >= 0) try { dm(c).remove(id) } catch (_: Exception) { }
        prefs(c).edit().remove(url).apply()
    }

    private fun mimeToExt(mime: String) = when {
        mime.contains("mp4") -> "mp4"
        mime.contains("matroska") -> "mkv"
        mime.contains("webm") -> "webm"
        mime.contains("quicktime") -> "mov"
        mime.contains("mpeg") && mime.startsWith("audio") -> "mp3"
        mime.contains("mp2t") -> "ts"
        mime.startsWith("audio/") -> "m4a"
        else -> null
    }

    /** Starts the download. [done] gets null on success or a short reason on failure (called on the main thread). */
    fun start(c: Context, url: String, title: String, done: (String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            val error = try { enqueue(app, url, title) } catch (e: Exception) { "Could not start the download" }
            main.post { done(error) }
        }.start()
    }

    private fun enqueue(c: Context, url: String, title: String): String? {
        val u = Uri.parse(url)
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "RTMP and RTSP streams cannot be downloaded"
        val path = (u.path ?: "").lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return "HLS and DASH streams cannot be downloaded"

        var mime = ""
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"; conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.instanceFollowRedirects = true
            if (conn.responseCode in 200..299) mime = (conn.contentType ?: "").lowercase().substringBefore(';')
            conn.disconnect()
        } catch (_: Exception) { }
        if (mime.contains("mpegurl") || mime.contains("dash+xml")) return "HLS and DASH streams cannot be downloaded"

        val fromPath = path.substringAfterLast('.', "").takeIf { it in known }
        val ext = fromPath ?: mimeToExt(mime) ?: "mp4"
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "video" }.take(80)
        val file = if (clean.lowercase().endsWith(".$ext")) clean else "$clean.$ext"

        val req = DownloadManager.Request(u)
            .setTitle(title)
            .setDescription("Gplay download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "$FOLDER/$file")
        if (mime.startsWith("video/") || mime.startsWith("audio/")) req.setMimeType(mime)
        val id = dm(c).enqueue(req)
        prefs(c).edit().putLong(url, id).apply()
        return null
    }
}
