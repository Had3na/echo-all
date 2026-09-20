package fr.nacre.media

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class CinemaKind(val label: String, val path: String) {
    MOVIE("Films", "movie"), TV("Séries", "tv"), ANIME("Animes", "anime")
}
internal data class CinemaTitle(
    val id: Int, val kind: CinemaKind, val title: String, val overview: String,
    val poster: String, val year: String, val rating: String, val genres: List<String> = emptyList(),
    val seasons: List<Int> = emptyList(), val trailer: String = ""
)
internal data class CinemaPage<T>(val entries: List<T>, val more: Boolean)
internal data class CinemaEpisode(val number: Int, val title: String, val date: String, val overview: String = "")
internal data class CinemaOffer(val name: String, val type: String)
internal data class CinemaAvailability(val offers: List<CinemaOffer>, val link: String)
internal fun JSONObject.cinemaText(key: String): String = if (isNull(key)) "" else optString(key).trim()
internal fun JSONArray?.cinemaObjects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
internal fun cinemaCountry(value: String): String = value.trim().uppercase(Locale.ROOT).takeIf {
    it in Locale.getISOCountries().toSet()
} ?: "FR"
private fun cinemaRating(j: JSONObject, key: String): String =
    j.optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0 }?.let { String.format(Locale.FRANCE, "%.1f/10", it) }.orEmpty()

internal fun parseCinemaTitle(j: JSONObject, kind: CinemaKind): CinemaTitle {
    val anime = kind == CinemaKind.ANIME
    val poster = if (anime) j.optJSONObject("images")?.optJSONObject("jpg")?.cinemaText("large_image_url").orEmpty()
        else j.cinemaText("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty()
    val trailerId = if (anime) j.optJSONObject("trailer")?.cinemaText("youtube_id").orEmpty() else
        j.optJSONObject("videos")?.optJSONArray("results").cinemaObjects().firstOrNull {
            it.cinemaText("site") == "YouTube" && it.cinemaText("type") == "Trailer" && it.optBoolean("official")
        }?.cinemaText("key").orEmpty()
    return CinemaTitle(
        j.optInt(if (anime) "mal_id" else "id"), kind,
        j.cinemaText(if (kind == CinemaKind.TV) "name" else "title"),
        j.cinemaText(if (anime) "synopsis" else "overview"), poster,
        (if (anime) j.optJSONObject("aired")?.cinemaText("from").orEmpty()
            else j.cinemaText(if (kind == CinemaKind.TV) "first_air_date" else "release_date")).take(4),
        cinemaRating(j, if (anime) "score" else "vote_average"),
        j.optJSONArray("genres").cinemaObjects().map { it.cinemaText("name") }.filter { it.isNotBlank() },
        j.optJSONArray("seasons").cinemaObjects().map { it.optInt("season_number", -1) }.filter { it >= 0 }.distinct(),
        trailerId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://www.youtube.com/watch?v=$it" }.orEmpty()
    )
}
internal fun parseCinemaSearch(j: JSONObject, kind: CinemaKind): CinemaPage<CinemaTitle> = CinemaPage(
    j.optJSONArray(if (kind == CinemaKind.ANIME) "data" else "results").cinemaObjects()
        .map { parseCinemaTitle(it, kind) }.filter { it.id > 0 && it.title.isNotBlank() }.distinctBy { it.id },
    if (kind == CinemaKind.ANIME) j.optJSONObject("pagination")?.optBoolean("has_next_page") == true
    else j.optInt("page", 1) < j.optInt("total_pages", 1)
)
internal fun parseCinemaEpisodes(j: JSONObject, anime: Boolean): CinemaPage<CinemaEpisode> = CinemaPage(
    j.optJSONArray(if (anime) "data" else "episodes").cinemaObjects().map {
        CinemaEpisode(it.optInt(if (anime) "mal_id" else "episode_number"),
            it.cinemaText(if (anime) "title" else "name"),
            it.cinemaText(if (anime) "aired" else "air_date").take(10), it.cinemaText("overview"))
    }.filter { it.number > 0 }.distinctBy { it.number },
    anime && j.optJSONObject("pagination")?.optBoolean("has_next_page") == true
)
internal fun parseCinemaAvailability(j: JSONObject, country: String): CinemaAvailability {
    val region = j.optJSONObject("results")?.optJSONObject(cinemaCountry(country)) ?: JSONObject()
    val offers = listOf("flatrate" to "Abonnement", "free" to "Gratuit", "ads" to "Avec publicité", "rent" to "Location", "buy" to "Achat")
        .flatMap { (key, label) -> region.optJSONArray(key).cinemaObjects().map { CinemaOffer(it.cinemaText("provider_name"), label) } }
        .filter { it.name.isNotBlank() }.distinct()
    val link = region.cinemaText("link").takeIf { runCatching {
        val uri = java.net.URI(it)
        uri.scheme == "https" && uri.host in listOf("www.themoviedb.org", "themoviedb.org") && uri.userInfo == null
    }.getOrDefault(false) }.orEmpty()
    return CinemaAvailability(offers, link)
}
