package fr.nacre.media

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

private const val USER_AGENT = "Echo-All/0.8.0 (Android; application personnelle)"

class HttpStatusException(val code: Int) : IOException("HTTP $code")

object Online {
    private val musicBrainzGate = Mutex()
    private var lastMusicBrainz = 0L

    private fun read(url: String, accept: String, max: Int): ByteArray {
        require(url.startsWith("https://"))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Accept", accept)
        }
        try {
            if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val block = ByteArray(16_384)
                while (true) { val n = input.read(block); if (n < 0) break; out.write(block, 0, n); if (out.size() > max) throw IOException("Réponse trop volumineuse.") }
                return out.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private suspend fun json(url: String): JSONObject = withContext(Dispatchers.IO) {
        fun fetch() = JSONObject(String(read(url, "application/json", 8_000_000), Charsets.UTF_8))
        // MusicBrainz answers 503 when requests come too fast: wait and retry twice.
        repeat(2) { attempt ->
            try { return@withContext fetch() }
            catch (error: HttpStatusException) { if (error.code != 503 && error.code != 429) throw error; delay(1_500L * (attempt + 1)) }
        }
        fetch()
    }

    /** Null when the image does not exist (404). */
    suspend fun image(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try { read(url, "image/*", 8_000_000) } catch (error: HttpStatusException) { if (error.code == 404) null else throw error }
    }

    suspend fun findTags(title: String, artist: String): List<TagCandidate> = musicBrainzGate.withLock {
        // MusicBrainz allows about one request per second per application.
        val wait = 1_100 - (SystemClock.elapsedRealtime() - lastMusicBrainz)
        if (wait > 0) delay(wait)
        try { parseRecordings(json("https://musicbrainz.org/ws/2/recording?fmt=json&limit=12&query=" + pathSegment(recordingQuery(title, artist)))) }
        finally { lastMusicBrainz = SystemClock.elapsedRealtime() }
    }

    suspend fun searchArchive(text: String, kind: MediaKind, page: Int = 1): List<ArchiveResult> {
        val fields = listOf("identifier", "title", "creator", "year", "date", "licenseurl", "collection").joinToString("") { "&fl[]=$it" }
        return parseArchiveSearch(json("https://archive.org/advancedsearch.php?output=json&rows=40&page=$page&sort[]=downloads+desc$fields&q=" + pathSegment(archiveSearchQuery(text, kind))))
    }

    private val radioServers = listOf("de2", "de1", "fi1", "at1", "nl1")

    /** Radio Browser is run by volunteers on several mirrors: try them in turn. */
    suspend fun searchRadios(query: String, tag: String, country: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val path = radioSearchPath(query, tag, country)
        var failure: Exception? = null
        for (server in radioServers) {
            try { return@withContext parseStations(org.json.JSONArray(String(read("https://$server.api.radio-browser.info$path", "application/json", 8_000_000), Charsets.UTF_8))) }
            catch (error: Exception) { failure = error }
        }
        throw failure ?: IOException("Annuaire indisponible.")
    }

    /** Tells the directory a station was played (its popularity ranking relies on it). Best effort. */
    suspend fun countRadioClick(uuid: String) = withContext(Dispatchers.IO) {
        if (uuid.isNotBlank()) runCatching { read("https://de2.api.radio-browser.info/json/url/" + pathSegment(uuid), "application/json", 100_000) }
    }

    suspend fun archiveItem(identifier: String, kind: MediaKind): ArchiveItem =
        parseArchiveItem(identifier, json("https://archive.org/metadata/" + pathSegment(identifier)), kind)
}

data class PendingDownload(val id: Long, val kind: MediaKind, val title: String, val artist: String, val album: String, val coverUrl: String, val durationMs: Long)
data class DownloadProgress(val download: PendingDownload, val status: Int, val bytes: Long, val total: Long)

/** Downloads through Android's DownloadManager (notification, resumes in background) into Music/Echo-All or Movies/Echo-All. */
object FreeDownloads {
    /** Android 9 and older need the storage permission to write in Music/Movies. */
    val needsStoragePermission get() = Build.VERSION.SDK_INT < 29

    fun enqueue(context: Context, item: ArchiveItem, file: ArchiveFile, kind: MediaKind) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val base = safeFileName(if (file.artist.isNotBlank()) file.artist + " - " + file.title else file.title)
        val request = DownloadManager.Request(Uri.parse(file.url))
            .setTitle(file.title).setDescription("Echo-All · " + item.license)
            .setMimeType(mimeFor(file.extension)).addRequestHeader("User-Agent", USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(if (kind == MediaKind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC, "Echo-All/$base.${file.extension}")
        val id = manager.enqueue(request)
        StudioStore(context).addDownload(PendingDownload(id, kind, file.title, file.artist, file.album.ifBlank { item.title }, item.cover, file.durationMs))
    }

    fun progress(context: Context): List<DownloadProgress> {
        val pending = StudioStore(context).downloads()
        if (pending.isEmpty()) return emptyList()
        val manager = context.getSystemService(DownloadManager::class.java)
        val rows = HashMap<Long, Triple<Int, Long, Long>>()
        manager.query(DownloadManager.Query().setFilterById(*pending.map { it.id }.toLongArray()))?.use { c ->
            while (c.moveToNext()) rows[c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))] = Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
        }
        // A download cancelled from the notification disappears from DownloadManager: report it as failed.
        return pending.map { p -> rows[p.id]?.let { (status, bytes, total) -> DownloadProgress(p, status, bytes, total) } ?: DownloadProgress(p, DownloadManager.STATUS_FAILED, 0, 0) }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(DownloadManager::class.java).remove(id)
        StudioStore(context).removeDownload(id)
    }

    private suspend fun scan(context: Context, path: String, mime: String): Uri? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, uri -> if (continuation.isActive) continuation.resume(uri) }
        }
    }

    private fun readable(context: Context, uri: Uri) = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() != null }.getOrDefault(false)

    /** Library entry for a finished download: the MediaStore reference when readable, else the DownloadManager one. */
    suspend fun libraryItem(context: Context, progress: DownloadProgress): LibraryItem? = withContext(Dispatchers.IO) {
        val p = progress.download
        val manager = context.getSystemService(DownloadManager::class.java)
        val collection = if (p.kind == MediaKind.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        var media: Uri? = null; var local: String? = null
        manager.query(DownloadManager.Query().setFilterById(p.id))?.use { c ->
            if (c.moveToFirst()) {
                // Android 10+ fills "mediastore_uri" for public folders; not part of the public SDK, so read it only if present.
                val column = c.getColumnIndex("mediastore_uri")
                if (column >= 0) media = c.getString(column)?.let(Uri::parse)
                local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            }
        }
        if (media == null) local?.let { Uri.parse(it).path }?.let { path -> media = scan(context, path, mimeFor(path.substringAfterLast('.'))) }
        // Same identifier as the phone scan uses, so a later scan does not list the file twice.
        val normalized = media?.let { runCatching { ContentUris.withAppendedId(collection, ContentUris.parseId(it)) }.getOrNull() }
        val uri = normalized?.takeIf { readable(context, it) } ?: manager.getUriForDownloadedFile(p.id) ?: return@withContext null
        LibraryItem(uri.toString(), p.title, p.kind, "Internet Archive", durationMs = p.durationMs, artist = p.artist,
            folder = if (p.kind == MediaKind.VIDEO) "Movies/Echo-All" else "Music/Echo-All", addedAt = System.currentTimeMillis(), album = p.album, tagged = true)
    }
}
