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
import android.os.PowerManager
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TorrentDownloadService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ ->
        runCatching { TorrentStore.pauseAll() }
        stopSelf()
    })
    private var wakeLock: PowerManager.WakeLock? = null
    private var startupFailure: String? = null
    private var loop: Job? = null
    private var work: Deferred<List<String>>? = null
    private var currentId: String? = null
    private val pending = AtomicInteger()
    @Volatile private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Téléchargements torrent", NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification(null))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo-All:Torrents").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
            if (Build.VERSION.SDK_INT < 28) startupFailure = "Le moteur torrent nécessite Android 9 ou plus récent."
        } catch (error: RuntimeException) {
            // onCreate runs later than startForegroundService: the caller cannot catch this failure.
            startupFailure = "Android a refusé le démarrage du service torrent (${error.javaClass.simpleName}). Rouvre l’application et réessaie."
            runCatching { java.io.File(filesDir, "last-crash.txt").writeText(crashStack(error)) }
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        pending.incrementAndGet()
        scope.launch {
            try {
                TorrentStore.initialize(this@TorrentDownloadService)
                val id = intent?.getStringExtra("id")
                val failure = startupFailure
                if (failure != null) {
                    if (id != null) TorrentStore.change(id) { it.copy(state = TorrentState.FAILED, speed = 0, error = failure) }
                    return@launch
                }
                if (intent?.action == PAUSE) {
                    if (id != null) {
                        TorrentStore.change(id) { if (it.active) it.copy(state = if (currentId == id) TorrentState.STOPPING else TorrentState.PAUSED, speed = 0) else it }
                        if (currentId == id) work?.cancel()
                    } else {
                        TorrentStore.pauseAll(); currentId?.let { id -> TorrentStore.change(id) { it.copy(state = TorrentState.STOPPING) } }; work?.cancel()
                    }
                } else if (id != null) {
                    TorrentStore.change(id) { if (it.state in listOf(TorrentState.PAUSED, TorrentState.FAILED)) it.copy(state = TorrentState.QUEUED, error = "") else it }
                }
            } finally { pending.decrementAndGet(); if (startupFailure == null) pump() else stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun pump() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            try {
                while (isActive) {
                    val job = TorrentStore.active.value.firstOrNull { it.state == TorrentState.QUEUED } ?: break
                    currentId = job.id
                    TorrentStore.change(job.id) { it.copy(state = TorrentState.METADATA, error = "") }
                    work = scope.async(Dispatchers.IO) {
                        runTorrent(job, TorrentStore.metadata(this@TorrentDownloadService, job.id), TorrentStore.prepareFolder(this@TorrentDownloadService, job.id)) { update ->
                            TorrentStore.progress(update)
                            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(update)) }
                        }
                    }
                    try {
                        val files = work!!.await()
                        TorrentStore.change(job.id) { if (it.active) it.copy(state = TorrentState.DONE, downloaded = it.total, speed = 0, files = files) else it }
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                        TorrentStore.change(job.id) { if (it.active) it.copy(state = TorrentState.FAILED, speed = 0,
                            error = (e.message ?: "Le moteur torrent est indisponible.").take(250)) else it }
                                        } finally {
                        withContext(NonCancellable) { work?.join() }
                        TorrentStore.change(job.id) { if (it.state == TorrentState.STOPPING) it.copy(state = TorrentState.PAUSED, speed = 0) else it }
                        work = null; currentId = null
                    }
                }
            } finally {
                if (pending.get() == 0) stopSelfResult(lastStart)
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch { TorrentStore.pauseAll(); currentId?.let { id -> TorrentStore.change(id) { it.copy(state = TorrentState.STOPPING) } }; work?.cancel(); stopSelf() }
    }
    override fun onDestroy() {
        scope.cancel()
        // Persist pause before releasing the executor; native shutdown finishes in runTorrent's finally.
        runCatching { TorrentStore.pauseAll() }
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        dispatcher.close()
        super.onDestroy()
    }
    private fun notification(job: TorrentJob?): Notification {
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 71, Intent(this, TorrentDownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (job?.state) {
            TorrentState.METADATA -> "Recherche des métadonnées…"
            TorrentState.CHECKING -> "Vérification des fichiers…"
            TorrentState.RUNNING -> "${job.peers} pairs · ${job.speed / 1024} Ko/s"
            else -> "Préparation…"
        }
        return Notification.Builder(this, CHANNEL).setContentTitle(job?.title ?: "Téléchargement torrent")
            .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tout mettre en pause", pause).build())
            .setProgress(100, if (job != null && job.total > 0) (100.0 * job.downloaded / job.total).toInt().coerceIn(0, 100) else 0, job == null || job.total == 0L)
            .build()
    }
    companion object {
        private const val CHANNEL = "torrent-downloads"
        private const val NOTIFICATION = 4320
        private const val PAUSE = "fr.nacre.media.TORRENT_PAUSE"
        fun start(context: Context, id: String) { context.startForegroundService(Intent(context, TorrentDownloadService::class.java).putExtra("id", id)) }
        fun pause(context: Context, id: String) { context.startService(Intent(context, TorrentDownloadService::class.java).setAction(PAUSE).putExtra("id", id)) }
    }
}
