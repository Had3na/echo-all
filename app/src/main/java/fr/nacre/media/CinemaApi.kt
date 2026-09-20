package fr.nacre.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Separate from media URLs: these endpoints return metadata, never playable streams. */
internal class CinemaApi(private val token: String) {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        private val jikanGate = Mutex()
        private var jikanNext = 0L
    }
    private val gate = Mutex()
    private val cache = LinkedHashMap<String, Pair<Long, String>>()
    /** Guards the cache map only. Holding it across the network would queue every request behind one. */
    private suspend fun cached(key: String): JSONObject? = gate.withLock {
        cache[key]?.takeIf { System.nanoTime() - it.first < TimeUnit.MINUTES.toNanos(10) }?.let { JSONObject(it.second) }
    }

    private suspend fun store(key: String, data: JSONObject) = gate.withLock {
        if (cache.size >= 80) cache.remove(cache.keys.first())
        cache[key] = System.nanoTime() to data.toString()
    }

    /**
     * The mutex used to wrap this whole method, network and retries included. Opening a title fires
     * three requests at once — details, providers, episodes — and they ran strictly one after the
     * other; worse, a 429 carrying Retry-After held the lock while it slept, freezing every other
     * request for that whole delay. Only the cache needs guarding.
     */
    private suspend fun request(path: String, anime: Boolean, params: Map<String, String> = emptyMap()): JSONObject {
        if (!anime && token.isBlank()) throw IOException("Ajoute ton jeton de lecture TMDB dans les réglages du catalogue.")
        val url = (if (anime) "https://api.jikan.moe/v4/" else "https://api.themoviedb.org/3/")
            .plus(path).toHttpUrl().newBuilder().apply {
                if (!anime) addQueryParameter("language", "fr-FR")
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        val key = url.toString()
        cached(key)?.let { return it }
        suspend fun fetch(): JSONObject {
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                val response = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).header("Accept", "application/json")
                        .header("User-Agent", "Echo-All")
                    if (!anime) req.header("Authorization", "Bearer ${token.trim()}")
                    client.newCall(req.build()).execute().use { res ->
                        val wait = res.header("Retry-After")?.toLongOrNull()
                        val body = if (res.isSuccessful) {
                            val source = res.body?.source() ?: throw IOException("Réponse vide.")
                            if (source.request(4_000_001)) throw IOException("Réponse trop volumineuse.")
                            source.readUtf8()
                        } else ""
                        Triple(res.code, wait, body)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (response.first in 200..299) return JSONObject(response.third)
                if (response.first in listOf(429, 502, 503, 504) && attempt < 2) {
                    val seconds = response.second ?: (2L * (attempt + 1))
                    if (seconds > 30) throw IOException("Service très sollicité. Réessaie dans $seconds secondes.")
                    delay(seconds.coerceAtLeast(1) * 1000)
                } else throw IOException(when (response.first) {
                    401, 403 -> "Accès refusé : vérifie ton jeton TMDB."
                    404 -> "Cette fiche n’est plus disponible."
                    429 -> "Trop de requêtes. Réessaie dans un instant."
                    else -> "Catalogue indisponible (HTTP ${response.first})."
                })
            }
            throw IOException("Catalogue indisponible.")
        }
        // Jikan allows about one request a second, so those stay in single file on purpose.
        val data = if (anime) jikanGate.withLock {
            val wait = jikanNext - System.nanoTime()
            if (wait > 0) delay(TimeUnit.NANOSECONDS.toMillis(wait) + 1)
            try { fetch() } finally { jikanNext = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1100) }
        } else fetch()
        store(key, data)
        return data
    }

    suspend fun search(kind: CinemaKind, query: String, page: Int): CinemaPage<CinemaTitle> {
        val anime = kind == CinemaKind.ANIME
        val path = if (anime) { if (query.isBlank()) "top/anime" else "anime" }
            else if (query.isBlank()) "${kind.path}/popular" else "search/${kind.path}"
        val params = mutableMapOf("page" to page.toString())
        if (anime) { params["sfw"] = "true"; params["limit"] = "20" } else params["include_adult"] = "false"
        if (query.isNotBlank()) params[if (anime) "q" else "query"] = query
        return parseCinemaSearch(request(path, anime, params), kind)
    }
    suspend fun details(title: CinemaTitle): CinemaTitle {
        val anime = title.kind == CinemaKind.ANIME
        val data = request("${title.kind.path}/${title.id}" + if (anime) "/full" else "", anime,
            if (anime) emptyMap() else mapOf("append_to_response" to "videos"))
        return parseCinemaTitle(if (anime) data.getJSONObject("data") else data, title.kind)
    }
    suspend fun availability(title: CinemaTitle, country: String): CinemaAvailability =
        parseCinemaAvailability(request("${title.kind.path}/${title.id}/watch/providers", false), country)
    suspend fun episodes(title: CinemaTitle, season: Int, page: Int): CinemaPage<CinemaEpisode> {
        val anime = title.kind == CinemaKind.ANIME
        return parseCinemaEpisodes(request(if (anime) "anime/${title.id}/episodes" else "tv/${title.id}/season/$season",
            anime, if (anime) mapOf("page" to page.toString()) else emptyMap()), anime)
    }
}
