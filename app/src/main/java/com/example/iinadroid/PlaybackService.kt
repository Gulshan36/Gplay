package com.example.iinadroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/** Foreground service that keeps the process alive (and shows controls) during background playback. */
class PlaybackService : Service() {

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Gplay"
        val paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))

        fun action(icon: Int, label: String, act: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getBroadcast(
                this, code, Intent(act).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(if (paused) "Paused" else "Playing in background")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "Play" else "Pause", ACTION_PLAY_PAUSE, 1))
            .addAction(action(R.drawable.ic_close, "Stop", ACTION_STOP, 2))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIF_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 7
        const val ACTION_PLAY_PAUSE = "com.example.iinadroid.PLAY_PAUSE"
        const val ACTION_STOP = "com.example.iinadroid.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PAUSED = "paused"

        fun start(ctx: Context, title: String, paused: Boolean) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, PlaybackService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PAUSED, paused)
                )
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
