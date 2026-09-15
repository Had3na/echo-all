package fr.nacre.media

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Pure parsing of MusicBrainz and Internet Archive responses. Network calls live in OnlineServices.kt.

data class TagCandidate(val title: String, val artist: String, val album: String, val year: String,
    val releaseId: String, val groupId: String, val score: Int, val lengthMs: Long) {
    val thumbnail get() = if (groupId.isNotBlank()) "https://coverartarchive.org/release-group/$groupId/front-250" else "https://coverartarchive.org/release/$releaseId/front-250"
    /** Release-group art first (shared by all editions), then this exact release. */
    val covers get() = listOfNotNull(groupId.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-500" },
        releaseId.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-500" })
}

data class ArchiveResult(val identifier: String, val title: String, val creator: String, val year: String, val license: String) {
    val thumbnail get() = "https://archive.org/services/img/" + pathSegment(identifier)
}
data class ArchiveFile(val name: String, val title: String, val artist: String, val album: String, val track: Int, val durationMs: Long, val sizeBytes: Long, val url: String) {
    val extension get() = name.substringAfterLast('.', "").lowercase()
}
data class ArchiveItem(val identifier: String, val title: String, val creator: String, val year: String, val license: String, val files: List<ArchiveFile>) {
    val cover get() = "https://archive.org/services/img/" + pathSegment(identifier)
}

private val AUDIO_FORMATS = listOf("VBR MP3", "MP3", "320Kbps MP3", "256Kbps MP3", "128Kbps MP3", "Ogg Vorbis", "Flac", "24bit Flac", "Apple Lossless Audio", "AAC", "M4A", "WAVE", "64Kbps MP3")
private val VIDEO_FORMATS = listOf("h.264", "h.264 IA", "MPEG4", "h.264 HD", "512Kb MPEG4", "Ogg Video", "WebM", "Matroska")
private val AUDIO_EXTENSIONS = listOf("mp3", "ogg", "opus", "m4a", "flac", "wav")
private val VIDEO_EXTENSIONS = listOf("mp4", "m4v", "webm", "ogv", "mkv")

fun pathSegment(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** Quoted Lucene term for the MusicBrainz search syntax. */
fun luceneTerm(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun recordingQuery(title: String, artist: String): String =
    "recording:" + luceneTerm(title.trim()) + if (artist.isBlank()) "" else " AND artist:" + luceneTerm(artist.trim())

/** Best first guess from a file name such as "03 - Artiste - Titre.mp3" or "Artiste_-_Titre". */
fun guessTags(title: String, artist: String): Pair<String, String> {
    var name = title.replace('_', ' ').replace(Regex("\\.(mp3|m4a|flac|ogg|opus|wav|aac|wma)$", RegexOption.IGNORE_CASE), "")
    name = name.replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s*"), "").replace(Regex("\\s+"), " ").trim()
    if (artist.isBlank() && " - " in name) return name.substringAfter(" - ").trim() to name.substringBefore(" - ").trim()
    return name to artist.trim()
}

private fun JSONObject.text(key: String): String = when (val value = opt(key)) {
    is JSONArray -> List(value.length()) { value.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
    null, JSONObject.NULL -> ""
    else -> value.toString()
}

fun parseRecordings(json: JSONObject): List<TagCandidate> {
    val recordings = json.optJSONArray("recordings") ?: return emptyList()
    return List(recordings.length()) { recordings.getJSONObject(it) }.mapNotNull { r ->
        val credits = r.optJSONArray("artist-credit")
        val artist = if (credits == null) "" else List(credits.length()) { i -> credits.optJSONObject(i)?.let { c -> c.optString("name") + c.optString("joinphrase") }.orEmpty() }.joinToString("").trim()
        val releases = r.optJSONArray("releases")?.let { list -> List(list.length()) { list.getJSONObject(it) } }.orEmpty()
        // Prefer an official album over singles, compilations or bootlegs.
        val release = releases.maxByOrNull { rel ->
            val group = rel.optJSONObject("release-group")
            (if (rel.optString("status") == "Official") 2 else 0) + (if (group?.optString("primary-type") == "Album") 1 else 0) - (if ((group?.optJSONArray("secondary-types")?.length() ?: 0) > 0) 1 else 0)
        }
        val title = r.optString("title")
        if (title.isBlank()) null
        else TagCandidate(title, artist, release?.optString("title").orEmpty(),
            (release?.optString("date")?.takeIf { it.isNotBlank() } ?: r.optString("first-release-date")).take(4),
            release?.optString("id").orEmpty(), release?.optJSONObject("release-group")?.optString("id").orEmpty(), r.optInt("score"), r.optLong("length"))
    }
}

fun archiveSearchQuery(text: String, kind: MediaKind): String {
    val words = text.trim().replace(Regex("[()\\[\\]{}\":^~*?\\\\/]"), " ").replace(Regex("\\s+"), " ").trim()
    val type = if (kind == MediaKind.VIDEO) "movies" else "audio"
    // Only items with a declared licence (Creative Commons / public domain) or artist-approved archives, never lending-only items.
    val allowed = if (kind == MediaKind.VIDEO) "(licenseurl:* OR collection:(prelinger) OR collection:(feature_films))" else "(licenseurl:* OR collection:(etree))"
    return (if (words.isBlank()) "" else "($words) AND ") + "mediatype:($type) AND $allowed AND -access-restricted-item:(true)"
}

fun licenseLabel(url: String, collections: List<String> = emptyList()): String {
    val u = url.lowercase()
    return when {
        "publicdomain/zero" in u -> "Domaine public (CC0)"
        "publicdomain" in u -> "Domaine public"
        "creativecommons.org/licenses/" in u -> {
            val parts = u.substringAfter("/licenses/").trim('/').split('/')
            "CC " + parts[0].uppercase() + (parts.getOrNull(1)?.let { " $it" } ?: "")
        }
        u.isNotBlank() -> "Licence indiquée par l’auteur"
        "etree" in collections -> "Live Music Archive · partage autorisé"
        "prelinger" in collections || "feature_films" in collections -> "Domaine public"
        else -> "Licence libre"
    }
}

private fun JSONObject.list(key: String): List<String> = when (val value = opt(key)) {
    is JSONArray -> List(value.length()) { value.optString(it) }
    is String -> listOf(value)
    else -> emptyList()
}

fun parseArchiveSearch(json: JSONObject): List<ArchiveResult> {
    val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
    return List(docs.length()) { docs.getJSONObject(it) }.mapNotNull { d ->
        val id = d.optString("identifier")
        if (id.isBlank()) null
        else ArchiveResult(id, d.text("title").ifBlank { id }, d.text("creator"), d.text("year").ifBlank { d.text("date").take(4) }, licenseLabel(d.text("licenseurl"), d.list("collection")))
    }
}

/** Seconds as "137.25" or "2:17" / "1:02:03". */
fun parseLength(text: String): Long {
    val t = text.trim()
    if (t.isEmpty()) return 0
    if (':' in t) return t.split(':').fold(0.0) { total, part -> total * 60 + (part.toDoubleOrNull() ?: return 0) }.times(1000).toLong()
    return ((t.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}

/** Jamendo-style file titles repeat the track number and artist: "1 - Artiste - Titre". */
fun cleanArchiveTitle(title: String, artist: String): String {
    var t = title.replace(Regex("^\\s*\\d{1,3}\\s*-\\s*"), "").trim()
    if (artist.isNotBlank() && t.startsWith("$artist - ", ignoreCase = true)) t = t.substring(artist.length + 3).trim()
    return t
}

fun parseArchiveItem(identifier: String, json: JSONObject, kind: MediaKind): ArchiveItem {
    val meta = json.optJSONObject("metadata") ?: JSONObject()
    val creator = meta.text("creator")
    val files = json.optJSONArray("files")?.let { list -> List(list.length()) { list.getJSONObject(it) } }.orEmpty()
        .filter { "/" !in it.optString("name") && !it.optString("name").startsWith("__") }
    val formats = if (kind == MediaKind.VIDEO) VIDEO_FORMATS else AUDIO_FORMATS
    val extensions = if (kind == MediaKind.VIDEO) VIDEO_EXTENSIONS else AUDIO_EXTENSIONS
    // One format only, so an album is not listed once as FLAC and again as MP3.
    val format = formats.firstOrNull { f -> files.any { it.optString("format") == f } }
    val chosen = if (format != null) files.filter { it.optString("format") == format }
        else extensions.firstNotNullOfOrNull { ext -> files.filter { it.optString("name").lowercase().endsWith(".$ext") }.takeIf { it.isNotEmpty() } }.orEmpty()
    val parsed = chosen.map { f ->
        val name = f.optString("name")
        val artist = f.text("artist").ifBlank { f.text("creator") }.ifBlank { creator }
        ArchiveFile(name, cleanArchiveTitle(f.text("title"), artist).ifBlank { name.substringBeforeLast('.') }, artist,
            f.text("album").ifBlank { meta.text("title") }, f.text("track").substringBefore('/').trim().toIntOrNull() ?: 0,
            parseLength(f.text("length")), f.text("size").toLongOrNull() ?: 0, "https://archive.org/download/" + pathSegment(identifier) + "/" + name.split('/').joinToString("/") { pathSegment(it) })
    }.sortedWith(compareBy<ArchiveFile> { if (it.track == 0) Int.MAX_VALUE else it.track }.thenBy { it.name })
    return ArchiveItem(identifier, meta.text("title").ifBlank { identifier }, creator, meta.text("year").ifBlank { meta.text("date").take(4) },
        licenseLabel(meta.text("licenseurl"), meta.list("collection")), parsed)
}

data class RadioStation(val uuid: String, val name: String, val url: String, val favicon: String, val tags: List<String>,
    val country: String, val codec: String, val bitrate: Int) {
    val details get() = listOf(country, tags.take(3).joinToString(", "), listOf(codec.takeUnless { it.equals("UNKNOWN", true) }.orEmpty(), if (bitrate > 0) "$bitrate kb/s" else "").filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Radio Browser stations: only working HTTPS streams (Android blocks unencrypted audio), one entry per stream. */
fun parseStations(json: JSONArray): List<RadioStation> = List(json.length()) { json.optJSONObject(it) }.filterNotNull().mapNotNull { s ->
    val url = s.optString("url_resolved").ifBlank { s.optString("url") }.trim()
    val name = s.optString("name").trim()
    if (!url.startsWith("https://") || name.isBlank() || s.optInt("lastcheckok", 1) != 1) null
    else RadioStation(s.optString("stationuuid"), name.take(120), url, s.optString("favicon").trim().takeIf { it.startsWith("https://") }.orEmpty(),
        s.optString("tags").split(',').map { it.trim() }.filter { it.isNotBlank() && it.length <= 30 }.distinct(),
        s.optString("countrycode").uppercase(), s.optString("codec").trim(), s.optInt("bitrate"))
}.distinctBy { it.url }

fun radioSearchPath(query: String, tag: String, country: String): String = buildString {
    append("/json/stations/search?hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=60")
    if (query.isNotBlank()) append("&name=").append(pathSegment(query.trim()))
    if (tag.isNotBlank()) append("&tag=").append(pathSegment(tag)).append("&tagExact=false")
    if (country.isNotBlank()) append("&countrycode=").append(pathSegment(country))
}

fun safeFileName(text: String): String =
    text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(90).ifBlank { "media" }

fun mimeFor(extension: String): String = when (extension.lowercase()) {
    "mp3" -> "audio/mpeg"; "ogg", "opus" -> "audio/ogg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
    "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "ogv" -> "video/ogg"; "mkv" -> "video/x-matroska"
    else -> "application/octet-stream"
}
