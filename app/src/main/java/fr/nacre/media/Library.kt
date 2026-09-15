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
    val tagged: Boolean = false
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
                item.optBoolean("scanned"), item.optLong("durationMs"), item.optString("artist"), item.optString("folder"), item.optLong("addedAt"), item.optString("album"), item.optBoolean("tagged"))
        }
    }
    suspend fun save(items: List<LibraryItem>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().apply {
            put("uri", item.uri); put("title", item.title); put("kind", item.kind.name)
            put("source", item.source); put("favorite", item.favorite)
            put("scanned", item.scanned); put("durationMs", item.durationMs); put("artist", item.artist)
            put("folder", item.folder); put("addedAt", item.addedAt); put("album", item.album); put("tagged", item.tagged)
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

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhoneLibrary(application)
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = _items.asStateFlow()
    val busy = MutableStateFlow(true)
    val message = MutableStateFlow<String?>(null)
    private var writable = true
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
            if (text) update { list -> list.map { if (it.uri == item.uri) it.copy(title = match.title, artist = match.artist, album = match.album.ifBlank { it.album }, tagged = true) else it } }
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

    fun favorite(item: LibraryItem) = viewModelScope.launch {
        try { update { list -> list.map { if (it.uri == item.uri) it.copy(favorite = !it.favorite) else it } } }
        catch (_: Exception) { message.value = "Modification non enregistrée." }
    }

    fun remove(item: LibraryItem) = viewModelScope.launch {
        try {
            if (item.scanned) settings.edit().putStringSet("hidden", settings.getStringSet("hidden", emptySet()).orEmpty() + item.uri).apply()
            update { list -> list.filterNot { it.uri == item.uri } }
            // Retain URI grants: a removed track may still be in the playback queue.
            message.value = "Retiré de Echo-All. Le fichier reste sur ton téléphone."
        } catch (_: Exception) { message.value = "Impossible de retirer ce média." }
    }
}
