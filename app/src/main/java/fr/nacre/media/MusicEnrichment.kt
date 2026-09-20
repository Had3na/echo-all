package fr.nacre.media

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Owned by the playback service; audio files are never uploaded. */
class MusicEnrichment(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var requested = ""
    private var requestedId = ""
    private var retryAfter = Long.MAX_VALUE
    fun close() { scope.cancel() }
    fun retry() { requested = "" }
    fun request(item: MediaItem?, duration: Long, playing: Boolean, live: Boolean) {
        val tags = prefs.getBoolean("autoMetadata", true)
        val lyrics = prefs.getBoolean("autoLyrics", true)
        if (prefs.getBoolean("private", false) || (!tags && !lyrics) || item == null || item.mediaMetadata.extras?.getBoolean("video") == true || live) {
            job?.cancel(); requested = ""; requestedId = ""; return
        }
        if (item.mediaId != requestedId) { job?.cancel(); requested = "" }
        if (!playing || duration <= 0) return
        val key = item.mediaId + "|" + tags + lyrics + "|" + item.mediaMetadata.title + "|" + item.mediaMetadata.artist
        if (requested == key && System.currentTimeMillis() < retryAfter) return
        requested = key; requestedId = item.mediaId; retryAfter = Long.MAX_VALUE
        job?.cancel()
        job = scope.launch {
            delay(1800)
            try { withContext(Dispatchers.IO) { enrich(item, duration, tags, lyrics) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                retryAfter = System.currentTimeMillis() + 60_000
                withContext(Dispatchers.IO) { StudioStore(app).cache("status:" + item.mediaId, "Recherche indisponible. Nouvelle tentative dans une minute.") }
            }
        }
    }
    private suspend fun enrich(item: MediaItem, duration: Long, tags: Boolean, lyrics: Boolean) {
        val store = StudioStore(app)
        val id = item.mediaId
        val metadata = item.mediaMetadata
        val blocked = metadata.extras?.getBoolean("autoMetadataBlocked") == true || store.cached("autoBlocked:$id") == "true"
        val suppliedArtist = metadata.artist?.toString().orEmpty().takeUnless { it in listOf("Téléphone", "Streaming", "Internet Archive", "<unknown>", "Radio") }.orEmpty()
        val guessed = guessTags(metadata.title?.toString().orEmpty(), suppliedArtist)
        var title = guessed.first; var artist = guessed.second; var album = metadata.albumTitle?.toString().orEmpty()
        val saved = store.cached("tags:$id")?.let(::JSONObject)
        if (saved != null && (metadata.extras?.getBoolean("tagged") != true || saved.optBoolean("manual"))) { title = saved.optString("title", title); artist = saved.optString("artist", artist); album = saved.optString("album", album) }
        if (artist.isBlank() || title.isBlank()) {
            store.cache("status:$id", "Renseigne le titre et l’artiste avec « Trouver pochette et infos », puis relance la recherche."); return
        }
        store.cache("status:$id", "Recherche en cours…")
        val now = System.currentTimeMillis()
        val lastTry = store.cached("attempt:$id")?.toLongOrNull() ?: 0L
        var coverUrls = saved?.optJSONArray("covers")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        if (tags && !blocked && saved == null && now - lastTry > 86_400_000) {
            try {
                val match = automaticTag(title, artist, duration, Online.findTags(title, artist))
                currentCoroutineContext().ensureActive()
                store.cache("attempt:$id", now.toString())
                if (match != null && store.cached("tags:$id") == null && store.cached("autoBlocked:$id") != "true") {
                    if (metadata.extras?.getBoolean("tagged") != true) { title = match.title; artist = match.artist; album = match.album }
                    coverUrls = match.covers
                    store.cache("tags:$id", JSONObject().put("title", title).put("artist", artist).put("album", album).put("covers", JSONArray(coverUrls)).toString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Lyrics can still succeed if MusicBrainz is unavailable. */ }
        }
        currentCoroutineContext().ensureActive()
        if (tags && !blocked && store.cached("autoBlocked:$id") != "true" && store.cover(id) == null && coverUrls.isNotEmpty()) {
            try { Covers.saveFromUrls(app, id, coverUrls, onlyIfMissing = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        if (!lyrics) { store.cache("status:$id", "Recherche des paroles désactivée dans les réglages."); return }
        val signature = normalizedMusic(title) + "|" + normalizedMusic(artist)
        val previousRaw = store.cached("lyrics:$id")
        val previous = previousRaw?.let(::JSONObject)
        if (previous?.optString("source") == "Fichier LRC" || (previous?.optString("signature") == signature && (previous.optString("lrc").isNotBlank() || previous.optBoolean("instrumental") || now - previous.optLong("checked") < 86_400_000))) {
            store.cache("status:$id", ""); return
        }
        val url = "https://lrclib.net/api/get?track_name=" + pathSegment(title) + "&artist_name=" + pathSegment(artist) + "&album_name=" + pathSegment(album) + "&duration=" + duration / 1000.0
        val result = try { Online.json(url) } catch (error: HttpStatusException) { if(error.code == 404) null else throw error }
        currentCoroutineContext().ensureActive()
        if (store.cached("lyrics:$id") != previousRaw) return
        val data = JSONObject().put("checked", now).put("signature", signature).put("source", "LRCLIB")
        if (result != null && normalizedMusic(result.optString("trackName")) == normalizedMusic(title) && normalizedMusic(result.optString("artistName")) == normalizedMusic(artist) && kotlin.math.abs(result.optDouble("duration")*1000-duration) <= 3000) {
            data.put("lrc", result.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty())
            data.put("plain", result.optString("plainLyrics").takeUnless { it == "null" }.orEmpty())
            data.put("instrumental", result.optBoolean("instrumental"))
        }
        store.cache("lyrics:$id", data.toString()); store.cache("status:$id", "")
    }
}
