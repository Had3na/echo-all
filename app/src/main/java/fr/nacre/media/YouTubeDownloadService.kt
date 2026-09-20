package fr.nacre.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps yt-dlp alive while the phone is on another app. Without it Android freezes the process a
 * few seconds after Echo-All leaves the screen and the download never finishes.
 */
class YouTubeDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements YouTube", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progression des téléchargements depuis YouTube."
                setShowBadge(false)
            })
        goForeground(null)
        scope.launch {
            YouTubeDownloads.active.collect { list ->
                val current = list.firstOrNull { !it.finished }
                if (current == null) stopSelf()
                else runCatching { getSystemService(NotificationManager::class.java).notify(ONGOING, build(current)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(item: YouTubeDownload?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ONGOING, build(item), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ONGOING, build(item))
    }

    private fun build(item: YouTubeDownload?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val waiting = YouTubeDownloads.active.value.count { !it.finished } - 1
        val text = when {
            item == null -> "Préparation…"
            item.state == DownloadState.QUEUED -> "En attente…"
            item.state == DownloadState.PREPARING -> "Préparation du moteur…"
            item.state == DownloadState.UPDATING -> "Mise à jour de yt-dlp…"
            item.state == DownloadState.CONVERTING -> "Conversion…"
            else -> "%.0f %%".format(item.progress * 100)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(item?.title ?: "Téléchargement YouTube")
            .setContentText(if (waiting > 0) "$text · $waiting en attente" else text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .apply {
                if (item != null && item.state == DownloadState.RUNNING && item.progress > 0f)
                    setProgress(100, (item.progress * 100).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    companion object {
        private const val ONGOING = 4319
        private const val CHANNEL = "youtube-downloads"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, YouTubeDownloadService::class.java)) }
        }

        fun stopIfIdle(context: Context) {
            if (!YouTubeDownloads.running) runCatching { context.stopService(Intent(context, YouTubeDownloadService::class.java)) }
        }
    }
}
