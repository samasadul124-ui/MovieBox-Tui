package com.moviebox.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Foreground service that keeps the process alive while the Rust download
 * engine writes files. Progress itself is polled by [DownloadManager].
 */
class DownloadService : Service() {
    companion object {
        private const val ACTION_START = "com.moviebox.app.START_DOWNLOAD"
        private const val ACTION_CANCEL = "com.moviebox.app.CANCEL_DOWNLOAD"
        private const val CHANNEL = "moviebox_downloads"

        fun start(
            ctx: Context,
            name: String,
            url: String,
            headers: List<Pair<String, String>>
        ) {
            val ha = JSONArray()
            for ((k, v) in headers) ha.put(JSONArray().put(k).put(v))
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra("name", name)
                putExtra("url", url)
                putExtra("headers", ha.toString())
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context, id: Long) {
            ctx.startService(
                Intent(ctx, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra("id", id)
                }
            )
        }
    }

    private var active = 0

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                active++
                val name = intent.getStringExtra("name") ?: "video"
                val url = intent.getStringExtra("url") ?: ""
                val headers = mutableListOf<Pair<String, String>>()
                try {
                    val ha = JSONArray(intent.getStringExtra("headers") ?: "[]")
                    for (i in 0 until ha.length()) {
                        val p = ha.getJSONArray(i)
                        if (p.length() >= 2) headers.add(p.getString(0) to p.getString(1))
                    }
                } catch (_: Exception) {
                }
                DownloadManager.start(this, name, url, headers) {
                    if (--active <= 0) stopSelf()
                }
            }
            ACTION_CANCEL -> {
                DownloadManager.cancel(intent.getLongExtra("id", -1))
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("MovieBox")
            .setContentText("Downloading…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
