package fr.nacre.media

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TorrentSources(val pirateBay: Boolean = true, val archive: Boolean = true, val feeds: List<String> = emptyList(), val apiKey: String = "", val yts: Boolean = true)
internal object TorrentSearchApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    private suspend fun read(reference: String, max: Int, allowMagnet: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        var url = reference.toHttpUrl()
        repeat(5) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Une adresse HTTPS est nécessaire." }
            currentCoroutineContext().ensureActive()
            val response = client.newCall(Request.Builder().url(url).header("User-Agent", "Echo-All/0.21.0").build()).execute()
            response.use { res ->
                if (res.code in listOf(301, 302, 303, 307, 308)) {
                    val location = res.header("Location") ?: throw IOException("Redirection invalide.")
                    if (allowMagnet && location.startsWith("magnet:", true)) return@withContext torrentMagnet(location).toByteArray(Charsets.UTF_8)
                    url = url.resolve(location) ?: throw IOException("Redirection invalide.")
                } else {
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    val input = res.body?.source() ?: throw IOException("Réponse vide.")
                    if (input.request(max.toLong() + 1)) throw IOException("Réponse trop volumineuse.")
                    val bytes = input.readByteArray()
                    currentCoroutineContext().ensureActive()
                    return@withContext bytes
                }
            }
        }
        throw IOException("Trop de redirections.")
    }
    suspend fun search(query: String, sources: TorrentSources): TorrentSearchResult = supervisorScope {
        require(query.length <= 200 && torrentWords(query).length >= 2) { "Saisis un titre de 2 à 200 caractères." }
        val tasks = mutableListOf<Deferred<Pair<List<TorrentHit>, String?>>>()
        fun provider(name: String, block: suspend () -> List<TorrentHit>) {
            tasks += async {
                try { block() to null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList<TorrentHit>() to "$name : recherche indisponible. Vérifie la connexion ou la configuration." }
            }
        }
        if (sources.pirateBay) provider("The Pirate Bay") {
            val url = "https://apibay.org/q.php".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("cat", "200").build()
            parsePirateBay(JSONArray(String(read(url.toString(), 4_000_000), Charsets.UTF_8)))
        }
        if (sources.yts) provider("YTS") {
            val url = "https://movies-api.accel.li/api/v2/list_movies.json".toHttpUrl().newBuilder()
                .addQueryParameter("query_term", query).addQueryParameter("limit", "50").build()
            parseYts(JSONObject(String(read(url.toString(), 4_000_000), Charsets.UTF_8)))
        }
        if (sources.archive) provider("Internet Archive") {
            val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", archiveTorrentQuery(query)).addQueryParameter("output", "json")
                .addQueryParameter("rows", "25").addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
                .addQueryParameter("sort[]", "downloads desc").build()
            parseArchiveTorrents(JSONObject(String(read(url.toString(), 4_000_000), Charsets.UTF_8)))
        }
        sources.feeds.take(8).forEachIndexed { i, feed -> provider("Torznab ${i + 1}") {
            val url = feed.toHttpUrl().newBuilder().setQueryParameter("t", "search").setQueryParameter("q", query)
                .setQueryParameter("cat", "2000,5000").setQueryParameter("limit", "50")
                .apply { if (sources.apiKey.isNotBlank()) setQueryParameter("apikey", sources.apiKey) }.build()
            parseTorznab(String(read(url.toString(), 4_000_000), Charsets.UTF_8), "Torznab ${i + 1}")
        } }
        require(tasks.isNotEmpty()) { "Active au moins une source de recherche." }
        val results = tasks.awaitAll()
        TorrentSearchResult(rankTorrentHits(query, results.flatMap { it.first }), results.mapNotNull { it.second })
    }
    suspend fun enqueue(context: Context, hit: TorrentHit): String = withContext(Dispatchers.IO) {
        TorrentStore.initialize(context)
        val id = torrentIdentity(if (hit.magnet.isNotBlank()) hit.magnet else hit.id)
        val existing = TorrentStore.active.value.firstOrNull { it.id == id || (hit.magnet.isNotBlank() && it.magnet.isNotBlank() && torrentIdentity(it.magnet) == torrentIdentity(hit.magnet)) }
        suspend fun start(id: String) = withContext(NonCancellable) {
            val state = TorrentStore.active.value.firstOrNull { it.id == id }?.state
            if (state != TorrentState.DONE && state != TorrentState.STOPPING) {
                try { TorrentDownloadService.start(context, id) }
                catch (e: Exception) {
                    TorrentStore.change(id) { if (!it.active || it.state == TorrentState.QUEUED) it.copy(state = TorrentState.FAILED, error = "Démarrage impossible. Réessaie depuis Torrents.") else it }
                    throw e
                }
            }
        }
        if (existing != null) { currentCoroutineContext().ensureActive(); start(existing.id); return@withContext existing.id }
        var magnet = hit.magnet
        if (hit.magnet.isBlank()) {
            val url = if (hit.archiveId.isNotBlank()) {
                val data = JSONObject(String(read("https://archive.org/metadata/${hit.archiveId}", 8_000_000), Charsets.UTF_8))
                val file = data.optJSONArray("files").cinemaObjects().firstOrNull { it.cinemaText("name").endsWith(".torrent") }
                    ?: throw IOException("Ce titre ne propose plus de fichier torrent.")
                "https://archive.org/download/${hit.archiveId}/" + pathSegment(file.cinemaText("name"))
            } else hit.url
            val bytes = read(url, MAX_TORRENT_BYTES, allowMagnet = true)
            if (bytes.take(7).toByteArray().toString(Charsets.UTF_8).equals("magnet:", true)) magnet = torrentMagnet(bytes.toString(Charsets.UTF_8))
            else {
            require(bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) { "La source n’a pas renvoyé un fichier .torrent." }
            currentCoroutineContext().ensureActive()
            val metadata = TorrentStore.metadata(context, id)
            metadata.parentFile?.mkdirs(); metadata.writeBytes(bytes)
            }
        }
        currentCoroutineContext().ensureActive()
        val duplicate = if (magnet.isBlank()) null else TorrentStore.active.value.firstOrNull { it.magnet.isNotBlank() && torrentIdentity(it.magnet) == torrentIdentity(magnet) }
        if (duplicate != null) { start(duplicate.id); return@withContext duplicate.id }
        withContext(NonCancellable) { TorrentStore.add(TorrentJob(id, hit.title, magnet)); start(id) }
        id
    }
}
