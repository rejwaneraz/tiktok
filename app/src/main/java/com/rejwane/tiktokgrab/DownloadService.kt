package com.rejwane.tiktokgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A batch of downloads is worth a notification. The phone keeps this process alive while the user is
 * in another app, and the progress is visible without opening TikTok Grab.
 *
 * The service owns no work of its own — the loop stays in the activity and only reports here — so a
 * failure in this file can never break a download; every call is wrapped.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            channel(this)
            startForeground(ID, build(this, 0, 0))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(true) }
        super.onDestroy()
    }

    companion object {
        private const val ID = 41
        private const val CHANNEL = "downloads"

        private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = manager(context) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }

        private fun build(context: Context, done: Int, total: Int): Notification {
            val running = total > 0
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Saving videos")
                .setContentText(if (running) "$done of $total clips" else "Preparing")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total.coerceAtLeast(0), done, !running)
                .build()
        }

        fun start(context: Context, total: Int) {
            runCatching {
                channel(context)
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
                manager(context)?.notify(ID, build(context, 0, total))
            }
        }

        fun progress(context: Context, done: Int, total: Int) {
            runCatching { manager(context)?.notify(ID, build(context, done, total)) }
        }

        fun stop(context: Context) {
            runCatching {
                manager(context)?.cancel(ID)
                context.stopService(Intent(context, DownloadService::class.java))
            }
        }
    }
}
