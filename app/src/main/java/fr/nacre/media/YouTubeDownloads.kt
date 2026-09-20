package fr.nacre.media

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

// Downloads run through yt-dlp: unlike the playback path it can merge a video-only track with an
// audio one, so it reaches 1080p and above, and it writes tags and cover art through FFmpeg.

enum class DownloadFormat(val label: String, val ytdlpValue: String) {
    ORIGINAL("Format d’origine", "best"),
    M4A("M4A", "m4a"),
    MP3("MP3", "mp3");

    val details get() = when (this) {
        ORIGINAL -> "Aucune reconversion : la qualité reste exactement celle de YouTube."
        M4A -> "Lu par tout ce qui est récent. Reconverti si la source est en Opus."
        MP3 -> "Compatible avec les vieilles chaînes et autoradios. Reconversion systématique."
    }
}

enum class DownloadState { QUEUED, PREPARING, UPDATING, RUNNING, CONVERTING, DONE, FAILED, CANCELLED }

data class YouTubeDownload(
    val id: String,
    val watchUrl: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnail: String,
    val durationMs: Long,
    val video: Boolean,
    val state: DownloadState = DownloadState.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1,
    val error: String = "",
    val libraryItem: LibraryItem? = null,
) {
    val done get() = state == DownloadState.DONE
    val finished get() = state == DownloadState.DONE || state == DownloadState.FAILED || state == DownloadState.CANCELLED
}

object YouTubeDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = MutableStateFlow<List<YouTubeDownload>>(emptyList())
    val active: StateFlow<List<YouTubeDownload>> = jobs.asStateFlow()

    // yt-dlp spawns Python and FFmpeg: running several at once on a phone only makes each one slower.
    private val runner = Mutex()
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var engineReady = false

    val running get() = jobs.value.any { !it.finished }

    @Synchronized
    private fun startEngine(context: Context) {
        if (engineReady) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        engineReady = true
    }

    private fun unmetered(context: Context): Boolean = onUnmetered(context)

    /**
     * The yt-dlp shipped inside the APK falls behind YouTube within weeks, and every download then
     * fails with a 403 that looks like a bug in the app. Refresh it at most once a week, on Wi-Fi,
     * right before a download — the engine is being started anyway, so it costs nothing otherwise.
     */
    private suspend fun refreshWeekly(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val week = 7L * 24 * 60 * 60 * 1000
        if (System.currentTimeMillis() - prefs.getLong("ytdlpChecked", 0) < week) return false
        if (!unmetered(context)) return false
        prefs.edit().putLong("ytdlpChecked", System.currentTimeMillis()).apply()
        withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) } }
        return true
    }

    /** Fetches a newer yt-dlp than the one shipped in the APK, which is how extraction breakage gets fixed. */
    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        try {
            startEngine(context)
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("ytdlpChecked", System.currentTimeMillis()).apply()
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            when {
                status == null -> "Mise à jour impossible pour le moment."
                status.toString().contains("ALREADY", true) -> "yt-dlp est déjà à jour."
                else -> "yt-dlp mis à jour. Version " + (YoutubeDL.getInstance().versionName(context.applicationContext) ?: "inconnue") + "."
            }
        } catch (error: Exception) {
            "Mise à jour impossible : " + (error.message ?: "erreur inconnue")
        }
    }

    fun enqueue(context: Context, item: YouTubeResult, video: Boolean, audioQuality: AudioQuality,
                videoQuality: VideoQuality, format: DownloadFormat) {
        val id = (youtubeVideoId(item.url) ?: item.url) + if (video) "|v" else "|a"
        if (jobs.value.any { it.id == id && !it.finished }) return
        val (artist, title) = youtubeArtistTitle(item.title, item.uploader)
        start(context, YouTubeDownload(id, cleanWatchUrl(item.url), title, artist, "YouTube",
            item.thumbnail.ifBlank { youtubeThumbnail(item.videoId) }, item.durationMs, video),
            audioQuality, videoQuality, format)
    }

    /** Runs a failed download again without making the user find the track a second time. */
    fun retry(context: Context, id: String, audioQuality: AudioQuality, videoQuality: VideoQuality, format: DownloadFormat) {
        val job = jobs.value.firstOrNull { it.id == id && it.finished } ?: return
        start(context, job.copy(state = DownloadState.QUEUED, progress = 0f, etaSeconds = -1, error = "", libraryItem = null),
            audioQuality, videoQuality, format)
    }

    private fun start(context: Context, job: YouTubeDownload, audioQuality: AudioQuality,
                      videoQuality: VideoQuality, format: DownloadFormat) {
        jobs.value = jobs.value.filterNot { it.id == job.id } + job
        cancelled.remove(job.id)
        YouTubeDownloadService.start(context)
        scope.launch { runner.withLock { run(context.applicationContext, job, audioQuality, videoQuality, format) } }
    }

    fun cancel(context: Context, id: String) {
        cancelled.add(id)
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        update(id) { it.copy(state = DownloadState.CANCELLED) }
        YouTubeDownloadService.stopIfIdle(context)
    }

    /** Called once a finished download has been added to the library, so the card can disappear. */
    fun forget(id: String) {
        jobs.value = jobs.value.filterNot { it.id == id }
    }

    fun clearFinished() {
        jobs.value = jobs.value.filterNot { it.finished }
    }

    private fun update(id: String, change: (YouTubeDownload) -> YouTubeDownload) {
        jobs.value = jobs.value.map { if (it.id == id) change(it) else it }
    }

    /**
     * YouTube answers 403 on some format URLs even though the video itself is fine. Asking for a
     * precise format makes it more likely, so each retry loosens the request: first a different
     * player client, then no forced format at all.
     */
    private fun options(job: YouTubeDownload, request: YoutubeDLRequest, audioQuality: AudioQuality,
                        videoQuality: VideoQuality, format: DownloadFormat, attempt: Int) {
        when (attempt) {
            0 -> {}
            1 -> request.addOption("--extractor-args", "youtube:player_client=default,mweb")
            else -> request.addOption("--extractor-args", "youtube:player_client=tv,web_safari")
        }
        val loose = attempt >= 2
        if (job.video) {
            request.addOption("-f", if (loose) "best" else videoFormatSelector(videoQuality))
            request.addOption("--merge-output-format", "mp4")
        } else {
            request.addOption("-f", if (loose) "bestaudio/best" else audioFormatSelector(audioQuality))
            request.addOption("--extract-audio")
            request.addOption("--audio-format", format.ytdlpValue)
            request.addOption("--audio-quality", "0")
            request.addOption("--embed-thumbnail")
        }
    }

    /** A refusal tied to the chosen format or client is worth another try; a private video is not. */
    private fun worthRetrying(error: Exception): Boolean {
        val text = error.message ?: error.toString()
        return "403" in text || "Forbidden" in text || "Requested format" in text || "fragment" in text
    }

    private suspend fun run(context: Context, job: YouTubeDownload, audioQuality: AudioQuality,
                            videoQuality: VideoQuality, format: DownloadFormat) {
        if (job.id in cancelled) return
        val workDir = File(context.getExternalFilesDir(null), "youtube").apply { mkdirs() }
        try {
            // The very first run unpacks Python and FFmpeg out of the APK: that is the slow part.
            update(job.id) { it.copy(state = if (engineReady) DownloadState.RUNNING else DownloadState.PREPARING) }
            startEngine(context)
            if (System.currentTimeMillis() - context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getLong("ytdlpChecked", 0) >= 7L * 24 * 60 * 60 * 1000 && unmetered(context)) {
                update(job.id) { it.copy(state = DownloadState.UPDATING) }
                refreshWeekly(context)
            }
            var last: Exception? = null
            for (attempt in 0..2) {
                if (job.id in cancelled) { update(job.id) { it.copy(state = DownloadState.CANCELLED) }; return }
                val stamp = UUID.randomUUID().toString()
                try {
                    update(job.id) { it.copy(state = DownloadState.RUNNING, progress = 0f) }
                    val request = YoutubeDLRequest(job.watchUrl)
                    request.addOption("--no-playlist")
                    request.addOption("--no-mtime")
                    request.addOption("--no-part")
                    // The extension is only known once yt-dlp has chosen a format, so the file is found back by its stamp.
                    request.addOption("-o", File(workDir, "$stamp.%(ext)s").absolutePath)
                    options(job, request, audioQuality, videoQuality, format, attempt)
                    request.addOption("--embed-metadata")

                    withContext(Dispatchers.IO) {
                        YoutubeDL.getInstance().execute(request, job.id, false) { percent, eta, line ->
                            // Post-processing (audio extraction, muxing) runs after the bytes are in.
                            val converting = percent >= 100f || "[ExtractAudio]" in line || "[Merger]" in line || "[ffmpeg]" in line
                            update(job.id) {
                                if (it.state == DownloadState.CANCELLED) it
                                else it.copy(state = if (converting) DownloadState.CONVERTING else DownloadState.RUNNING,
                                    progress = (percent / 100f).coerceIn(0f, 1f), etaSeconds = eta)
                            }
                        }
                    }
                    if (job.id in cancelled) { clean(workDir, stamp); update(job.id) { it.copy(state = DownloadState.CANCELLED) }; return }

                    val produced = workDir.listFiles { f -> f.name.startsWith(stamp) }?.maxByOrNull { it.length() }
                        ?: throw IllegalStateException("yt-dlp n’a produit aucun fichier.")
                    val uri = publish(context, produced, job)
                        ?: throw IllegalStateException("Enregistrement impossible dans la médiathèque.")
                    produced.delete()

                    val entry = LibraryItem(uri.toString(), job.title, if (job.video) MediaKind.VIDEO else MediaKind.MUSIC,
                        "YouTube", durationMs = job.durationMs, artist = job.artist,
                        folder = if (job.video) "Movies/Echo-All" else "Music/Echo-All",
                        addedAt = System.currentTimeMillis(), album = job.album, tagged = true)
                    update(job.id) { it.copy(state = DownloadState.DONE, progress = 1f, libraryItem = entry) }
                    return
                } catch (error: Exception) {
                    clean(workDir, stamp)
                    if (job.id in cancelled) { update(job.id) { it.copy(state = DownloadState.CANCELLED) }; return }
                    last = error
                    if (!worthRetrying(error)) break
                }
            }
            update(job.id) { it.copy(state = DownloadState.FAILED, error = shortError(last)) }
        } catch (error: Exception) {
            if (job.id in cancelled) update(job.id) { it.copy(state = DownloadState.CANCELLED) }
            else update(job.id) { it.copy(state = DownloadState.FAILED, error = shortError(error)) }
        } finally {
            YouTubeDownloadService.stopIfIdle(context)
        }
    }

    private fun clean(workDir: File, stamp: String) {
        workDir.listFiles { f -> f.name.startsWith(stamp) }?.forEach { it.delete() }
    }

    private fun shortError(error: Exception?): String {
        val full = (error?.message ?: error?.toString()).orEmpty()
        val text = full.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        return when {
            "Sign in to confirm" in full || "bot" in full.lowercase() -> "YouTube demande une vérification. Réessaie plus tard."
            "Video unavailable" in full -> "Vidéo indisponible ou privée."
            // Survived all three attempts: the yt-dlp shipped in the APK is almost certainly too old.
            "403" in full || "Forbidden" in full -> "Refus de YouTube (403). Mets yt-dlp à jour dans les réglages."
            "Requested format" in full -> "Cette qualité n’existe pas pour cette vidéo."
            text.isBlank() -> "Téléchargement interrompu."
            else -> text.take(160)
        }
    }

    /** Moves the finished file into Musique/Echo-All or Films/Echo-All so the phone lists it like any other track. */
    private suspend fun publish(context: Context, source: File, job: YouTubeDownload): Uri? = withContext(Dispatchers.IO) {
        val extension = source.extension.lowercase().ifBlank { if (job.video) "mp4" else "m4a" }
        val mime = mimeFor(extension)
        val name = youtubeFileBase(job.artist, job.title) + "." + extension
        val collection = if (job.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val folder = (if (job.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/Echo-All"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (!job.video) {
                    put(MediaStore.Audio.Media.TITLE, job.title)
                    put(MediaStore.Audio.Media.ARTIST, job.artist)
                    put(MediaStore.Audio.Media.ALBUM, job.album)
                }
            }
            val uri = context.contentResolver.insert(collection, values) ?: return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: return@withContext null
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val base = Environment.getExternalStoragePublicDirectory(if (job.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC)
            val target = File(File(base, "Echo-All").apply { mkdirs() }, name)
            source.copyTo(target, overwrite = true)
            scan(context, target.absolutePath, mime) ?: Uri.fromFile(target)
        }
    }

    private suspend fun scan(context: Context, path: String, mime: String): Uri? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, uri ->
                if (continuation.isActive) continuation.resume(uri)
            }
        }
    }
}

data class DownloadUsage(val files: Int, val bytes: Long)

/**
 * How much room the downloaded files take. Read from the media index rather than the folder, because
 * from Android 10 on an application cannot list Musique/ and Films/ by path.
 */
suspend fun downloadUsage(context: Context): DownloadUsage = withContext(Dispatchers.IO) {
    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH
        else MediaStore.MediaColumns.DATA
    var files = 0
    var bytes = 0L
    listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
        runCatching {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns.SIZE, pathColumn), null, null, null)?.use { cursor ->
                val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val path = cursor.getColumnIndex(pathColumn)
                if (size >= 0 && path >= 0) while (cursor.moveToNext()) {
                    if (cursor.getString(path)?.contains("Echo-All") == true) { files++; bytes += cursor.getLong(size) }
                }
            }
        }
    }
    DownloadUsage(files, bytes)
}

/** True on Wi-Fi or any connection the phone does not count against a data plan. */
fun onUnmetered(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}.getOrDefault(false)

/** Streaming is refused on mobile data when the setting asks for it; downloads are never blocked. */
fun streamingBlocked(context: Context): Boolean =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wifiOnlyStreaming", false) &&
        !onUnmetered(context)

/** Android 9 and older write into Musique/ and Films/ directly, which needs the storage permission. */
val youtubeDownloadsNeedStorage get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
