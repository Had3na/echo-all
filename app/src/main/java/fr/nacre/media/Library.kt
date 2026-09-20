package fr.nacre.media

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class MediaKind { MUSIC, VIDEO, PHOTO }
data class LibraryItem(
    val uri: String,
    val title: String,
    val kind: MediaKind,
    val source: String = "Téléphone",
    val favorite: Boolean = false,
    val scanned: Boolean = false, val durationMs: Long = 0, val artist: String = "",
    val folder: String = "", val addedAt: Long = 0, val album: String = "",
    /** Title/artist/album chosen by the user (online lookup): a phone scan must not overwrite them. */
    val tagged: Boolean = false,
    val videoSection: String = "", val videoCategory: String = "",
    val metadataUndo: String = "", val autoMetadataBlocked: Boolean = false,
    /** From the phone index, on Android 11 and later. Empty everywhere else. */
    val genre: String = ""
)

// A NAS provider can supply the same model without changing the UI or playback layer.
interface MediaSource {
    val id: String
    suspend fun list(): List<LibraryItem>
}

class PhoneLibrary(context: Context) : MediaSource {
    override val id = "phone"
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    override suspend fun list(): List<LibraryItem> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            LibraryItem(item.getString("uri"), item.getString("title"),
                MediaKind.valueOf(item.getString("kind")), item.getString("source"), item.optBoolean("favorite"),
                item.optBoolean("scanned"), item.optLong("durationMs"), item.optString("artist"), item.optString("folder"), item.optLong("addedAt"), item.optString("album"), item.optBoolean("tagged"), item.optString("videoSection"), item.optString("videoCategory"), item.optString("metadataUndo"), item.optBoolean("autoMetadataBlocked"), item.optString("genre"))
        }
    }
    suspend fun save(items: List<LibraryItem>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().apply {
            put("uri", item.uri); put("title", item.title); put("kind", item.kind.name)
            put("source", item.source); put("favorite", item.favorite)
            put("scanned", item.scanned); put("durationMs", item.durationMs); put("artist", item.artist)
            put("folder", item.folder); put("addedAt", item.addedAt); put("album", item.album); put("tagged", item.tagged); put("videoSection", item.videoSection); put("videoCategory", item.videoCategory); put("metadataUndo", item.metadataUndo); put("autoMetadataBlocked", item.autoMetadataBlocked); put("genre", item.genre)
        }) }
        val output = file.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

/** A message that can be taken back. Separate from [LibraryViewModel.message] so that an
 *  ordinary message arriving meanwhile can never inherit somebody else's undo. */
data class Notice(val text: String, val undo: suspend () -> Unit)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhoneLibrary(application)
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = _items.asStateFlow()
    val busy = MutableStateFlow(true)
    val message = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<Notice?>(null)
    private var writable = true
    private val stopCompanion = StudioEvents.listen { key ->
        if (key.startsWith("companion:tags:")) viewModelScope.launch { applyAutomaticMetadata() }
    }
    override fun onCleared() { stopCompanion(); super.onCleared() }
    private suspend fun applyAutomaticMetadata() {
        try {
            val tags = withContext(Dispatchers.IO) { StudioStore(getApplication()).cacheAll("tags:") }
            if (_items.value.none { !it.tagged && it.uri in tags }) return
            update { list -> list.map { item ->
                val raw = tags[item.uri]
                if (raw == null) item else runCatching { applyAutomaticTags(item, raw) }.getOrDefault(item)
            } }
        } catch (_: Exception) { }
    }
    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Opens the studio database early (one-time migration from 0.6 happens here) and copies old linked covers in.
        viewModelScope.launch(Dispatchers.IO) { runCatching { Covers.adoptLinked(application) } }
        viewModelScope.launch {
            lock.withLock {
                try { _items.value = repository.list() }
                catch (_: Exception) {
                    writable = false
                    message.value = "Bibliothèque illisible. Redémarre l’application ; les données existantes sont conservées."
                } finally { busy.value = false }
            }
            applyAutomaticMetadata()
            collectDownloads()
        }
    }

    private suspend fun update(transform: (List<LibraryItem>) -> List<LibraryItem>) {
        lock.withLock {
            check(writable)
            val next = transform(_items.value)
            repository.save(next)
            _items.value = next
        }
    }

    fun scan(silent: Boolean = false) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val context = getApplication<Application>()
            val allowed = MediaKind.entries.filter { context.canScan(it) }.toSet()
            val result = scanPhone(context)
            val hidden = settings.getStringSet("hidden", emptySet()).orEmpty()
            val excluded = settings.getStringSet("excludedFolders", emptySet()).orEmpty()
            val minSeconds = settings.getInt("minAudioSeconds", 0)
            val filtered = result.copy(items = result.items.filter { item -> item.folder !in excluded && (item.kind != MediaKind.MUSIC || item.durationMs >= minSeconds * 1000L) })
            update { old -> mergeScan(old, filtered, allowed, hidden) }
            if (!silent || result.failed) message.value = when {
                allowed.isEmpty() -> "Accès refusé. Tu peux toujours ajouter des fichiers manuellement."
                result.failed -> "Scan partiel : certains fichiers sont temporairement inaccessibles."
                else -> "Scan terminé : "+result.items.size+" médias accessibles. Android peut limiter l’accès à ta sélection."
            }
        } catch (_: Exception) { if (!silent) message.value = "Le scan a échoué. Réessaie depuis Sources." }
        finally { busy.value = false }
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try {
            val context = getApplication<Application>()
            val (restored, root) = readBackup(context, uri)
            val commit = preparePreferences(context, root)
            update { old -> (restored + old).distinctBy { it.uri } }
            withContext(Dispatchers.IO) { commit() }
            message.value = "Sauvegarde restaurée. Les fichiers doivent rester accessibles sur ce téléphone."
        } catch (error: Exception) { message.value = "Restauration impossible : "+error.message }
        finally { busy.value = false }
    }

    /** Applies an online match: text fields and/or cover. */
    fun applyTags(item: LibraryItem, match: TagCandidate, text: Boolean, cover: Boolean) = viewModelScope.launch {
        try {
            if (text) withContext(Dispatchers.IO) { StudioStore(getApplication()).cache("tags:" + item.uri, JSONObject().put("title", match.title).put("artist", match.artist).put("album", match.album).put("manual", true).toString()) }
            if (text) update { list -> list.map { if (it.uri == item.uri) it.copy(title = match.title, artist = match.artist, album = match.album.ifBlank { it.album }, tagged = true, metadataUndo = "") else it } }
            val found = !cover || Covers.saveFromUrls(getApplication(), item.uri, match.covers)
            message.value = when {
                cover && !found -> if (text) "Informations enregistrées. Aucune pochette disponible pour cette sortie." else "Aucune pochette disponible pour cette sortie."
                text && cover -> "Informations et pochette enregistrées."
                cover -> "Pochette enregistrée."
                else -> "Informations enregistrées."
            }
        } catch (_: Exception) { message.value = "Impossible d’enregistrer : vérifie ta connexion et réessaie." }
    }

    /** Saves a radio in the library (Musique, folder "Radios") with its logo as cover. */
    fun addRadio(station: RadioStation) = viewModelScope.launch {
        try {
            val entry = LibraryItem(station.url, station.name, MediaKind.MUSIC, "Radio", artist = station.tags.take(2).joinToString(", "),
                folder = "Radios", addedAt = System.currentTimeMillis(), tagged = true)
            update { old -> (old + entry).distinctBy { it.uri } }
            message.value = "${station.name} ajoutée à ta bibliothèque."
            if (station.favicon.isNotBlank()) runCatching { Covers.saveFromUrls(getApplication(), station.url, listOf(station.favicon)) }
        } catch (_: Exception) { message.value = "Impossible d’ajouter cette radio." }
    }

    /**
     * Keeps a YouTube track without downloading it: the entry points at its watch address, exactly
     * as a radio entry points at its stream. The player already knows how to resolve that address,
     * so the track becomes favouritable, sortable into playlists and resumable like any other.
     */
    fun keepYouTube(watchUrl: String, title: String, artist: String, durationMs: Long, coverUrl: String) = viewModelScope.launch {
        try {
            val watch = cleanWatchUrl(watchUrl)
            if (_items.value.any { it.uri == watch }) { message.value = "Ce titre est déjà dans ta bibliothèque."; return@launch }
            val entry = LibraryItem(watch, title, MediaKind.MUSIC, "YouTube", artist = artist,
                folder = "YouTube", addedAt = System.currentTimeMillis(), durationMs = durationMs, tagged = true)
            update { old -> (old + entry).distinctBy { it.uri } }
            notice.value = Notice(title + " gardé dans ta bibliothèque.") {
                update { list -> list.filterNot { it.uri == watch } }
                message.value = "Retiré de ta bibliothèque."
            }
            if (coverUrl.isNotBlank()) runCatching {
                withContext(Dispatchers.IO) { Covers.saveFromUrls(getApplication(), watch, listOf(coverUrl)) }
            }
        } catch (_: Exception) { message.value = "Impossible de garder ce titre." }
    }

    /** Batch removal from a multiple selection: one message, one undo for the whole lot. */
    fun removeAll(items: List<LibraryItem>) = viewModelScope.launch {
        if (items.isEmpty()) return@launch
        try {
            val uris = items.map { it.uri }.toSet()
            val scanned = items.filter { it.scanned }.map { it.uri }
            if (scanned.isNotEmpty()) settings.edit().putStringSet("hidden",
                settings.getStringSet("hidden", emptySet()).orEmpty() + scanned).apply()
            update { list -> list.filterNot { it.uri in uris } }
            notice.value = Notice(
                if (items.size == 1) "Retiré de Echo-All." else "${items.size} médias retirés de Echo-All.") {
                if (scanned.isNotEmpty()) settings.edit().putStringSet("hidden",
                    settings.getStringSet("hidden", emptySet()).orEmpty() - scanned.toSet()).apply()
                update { list -> (list + items).distinctBy { it.uri } }
                message.value = "Retour à l’état précédent."
            }
        } catch (_: Exception) { message.value = "Impossible de retirer ces médias." }
    }

    /** Finished yt-dlp downloads: the files are already in Musique/Echo-All, only the entries are missing. */
    internal fun collectTorrent(job: TorrentJob) = viewModelScope.launch {
        try {
            val items = withContext(Dispatchers.IO) {
                job.files.mapNotNull { path ->
                    val mime = torrentMime(path)
                    val kind = when {
                        mime.startsWith("video/") -> MediaKind.VIDEO
                        mime.startsWith("audio/") -> MediaKind.MUSIC
                        mime.startsWith("image/") -> MediaKind.PHOTO
                        else -> return@mapNotNull null
                    }
                    val uri = TorrentStore.fileUri(getApplication(), job, path)
                    LibraryItem(uri.toString(), File(path).nameWithoutExtension, kind, "Torrent", folder = "Torrents/${job.title}", addedAt = System.currentTimeMillis())
                }
            }
            update { old -> (old + items).distinctBy { it.uri } }
            message.value = if (items.isEmpty()) "Aucun média compatible dans ce torrent." else "${items.size} médias ajoutés à la bibliothèque."
        } catch (_: Exception) { message.value = "Impossible d’ajouter les fichiers de ce torrent." }
    }

    fun collectYouTube() = viewModelScope.launch {
        val done = YouTubeDownloads.active.value.filter { it.done && it.libraryItem != null }
        if (done.isEmpty()) return@launch
        try {
            update { old -> (old + done.map { it.libraryItem!! }).distinctBy { it.uri } }
            val store = StudioStore(getApplication())
            done.forEach { job ->
                // yt-dlp already embeds the thumbnail; this only covers the formats where it could not.
                if (job.thumbnail.isNotBlank()) runCatching {
                    withContext(Dispatchers.IO) { Covers.saveFromUrls(getApplication(), job.libraryItem!!.uri, listOf(job.thumbnail), onlyIfMissing = true) }
                }
                youtubeVideoId(job.watchUrl)?.let { id ->
                    withContext(Dispatchers.IO) { runCatching { store.cache("yt:" + id, job.libraryItem!!.uri) } }
                }
                YouTubeDownloads.forget(job.id)
            }
            StudioEvents.emit("youtube")
            message.value = if (done.size == 1) "Téléchargé : ${done.first().title}" else "${done.size} téléchargements ajoutés à ta bibliothèque."
        } catch (_: Exception) { message.value = "Impossible d’ajouter ces téléchargements." }
    }

    private val collecting = Mutex()
    /** Adds finished downloads to the library (with their cover) and forgets failed ones. Safe to call often. */
    fun collectDownloads() = viewModelScope.launch {
        if (!collecting.tryLock()) return@launch
        try {
            val app = getApplication<Application>()
            val store = StudioStore(app)
            val states = withContext(Dispatchers.IO) { FreeDownloads.progress(app) }
            val done = states.filter { it.status == android.app.DownloadManager.STATUS_SUCCESSFUL }
                .mapNotNull { state -> FreeDownloads.libraryItem(app, state)?.let { state to it } }
            if (done.isNotEmpty()) {
                update { old -> (old + done.map { it.second }).distinctBy { it.uri } }
                done.forEach { (state, entry) ->
                    withContext(Dispatchers.IO) { runCatching { Covers.saveFromUrls(app, entry.uri, listOf(state.download.coverUrl)) }; store.removeDownload(state.download.id) }
                }
                message.value = if (done.size == 1) "Téléchargé : ${done.first().second.title}" else "${done.size} téléchargements ajoutés à ta bibliothèque."
            }
            val failed = states.filter { it.status == android.app.DownloadManager.STATUS_FAILED }
            if (failed.isNotEmpty()) {
                withContext(Dispatchers.IO) { failed.forEach { store.removeDownload(it.download.id) } }
                message.value = "Téléchargement interrompu : ${failed.first().download.title}. Réessaie."
            }
        } catch (_: Exception) { }
        finally { collecting.unlock() }
    }

    fun restoreHidden() {
        settings.edit().remove("hidden").apply()
        scan()
    }

    fun importMedia(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        busy.value = true
        try {
            val imported = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                uris.mapNotNull { uri -> runCatching {
                    val mime = resolver.getType(uri).orEmpty()
                    val kind = when {
                        mime.startsWith("audio/") -> MediaKind.MUSIC
                        mime.startsWith("video/") -> MediaKind.VIDEO
                        mime.startsWith("image/") -> MediaKind.PHOTO
                        else -> return@runCatching null
                    }
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val title = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Média sans titre"
                    LibraryItem(uri.toString(), title, kind, addedAt = System.currentTimeMillis())
                }.getOrNull() }
            }
            update { old -> (old + imported).distinctBy { it.uri } }
            val skipped = uris.size - imported.size
            message.value = if (skipped > 0) "Import terminé. $skipped fichier(s) non pris en charge ou sans accès durable."
                else "Médias ajoutés à ta bibliothèque."
        } catch (_: Exception) { message.value = "Impossible de sauvegarder l’import. Réessaie." }
        finally { busy.value = false }
    }

    fun stream(title: String, url: String, kind: MediaKind) = viewModelScope.launch {
        if (!validStreamUrl(url)) { message.value = "Utilise un lien direct HTTPS valide."; return@launch }
        try {
            update { old -> (old + LibraryItem(url.trim(), title.trim().ifBlank { "Mon flux" }, kind, "Streaming")).distinctBy { it.uri } }
            message.value = "Flux ajouté."
        } catch (_: Exception) { message.value = "Impossible d’enregistrer ce flux." }
    }

    fun undoMetadata(item: LibraryItem) = viewModelScope.launch {
        try {
            val current = _items.value.find { it.uri == item.uri } ?: return@launch
            if(current.metadataUndo.isBlank()) return@launch
            val restored = undoAutomaticTags(current)
            val store = StudioStore(getApplication())
            // Block in-flight automatic writes first. The service applies this explicit restore to its queue.
            withContext(Dispatchers.IO) {
                store.cache("autoBlocked:" + item.uri, "true")
                store.cache("tags:" + item.uri, JSONObject(metadataSnapshot(restored)).put("manual", true).put("blocked", true).toString())
            }
            update { list -> list.map { if(it.uri == item.uri) undoAutomaticTags(it) else it } }
            withContext(Dispatchers.IO) {
                val automaticCover = store.cached("autoCover:" + item.uri)
                if(automaticCover != null && store.cover(item.uri) == automaticCover) Covers.clear(getApplication(), item.uri)
            }
            message.value = "Informations d’origine restaurées. Recherche automatique désactivée pour ce morceau."
        } catch (_: Exception) { message.value = "Impossible d’annuler la correction." }
    }

    fun organizeVideo(item: LibraryItem, section: String, category: String) = viewModelScope.launch {
        try {
            val before = _items.value.find { it.uri == item.uri }
            update { list -> list.map { if (it.uri == item.uri && it.kind == MediaKind.VIDEO) it.copy(videoSection = section.takeIf { s -> s in VIDEO_SECTIONS } ?: "daily", videoCategory = category.trim().take(48)) else it } }
            if (before != null) notice.value = Notice("Vidéo classée.") {
                update { list -> list.map { if (it.uri == item.uri) it.copy(videoSection = before.videoSection, videoCategory = before.videoCategory) else it } }
                message.value = "Classement annulé."
            }
        } catch (_: Exception) { message.value = "Classement non enregistré." }
    }

    fun favorite(item: LibraryItem) = viewModelScope.launch {
        try { update { list -> list.map { if (it.uri == item.uri) it.copy(favorite = !it.favorite) else it } } }
        catch (_: Exception) { message.value = "Modification non enregistrée." }
    }

    fun remove(item: LibraryItem) = viewModelScope.launch {
        try {
            if (item.scanned) settings.edit().putStringSet("hidden", settings.getStringSet("hidden", emptySet()).orEmpty() + item.uri).apply()
            update { list -> list.filterNot { it.uri == item.uri } }
            // Retain URI grants: a removed track may still be in the playback queue.
            notice.value = Notice("Retiré de Echo-All. Le fichier reste sur ton téléphone.") {
                // Un-hiding only this one, where "Réafficher les médias masqués" would bring every one back.
                if (item.scanned) settings.edit().putStringSet("hidden",
                    settings.getStringSet("hidden", emptySet()).orEmpty() - item.uri).apply()
                update { list -> (list + item).distinctBy { it.uri } }
                message.value = item.title + " est de retour."
            }
        } catch (_: Exception) { message.value = "Impossible de retirer ce média." }
    }
}
