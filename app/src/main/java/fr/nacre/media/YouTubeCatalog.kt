package fr.nacre.media

import java.util.Locale

// Pure link parsing, title cleaning and quality choices for the YouTube module.
// Playback resolution lives in YouTubeService.kt, downloads in YouTubeDownloads.kt.

enum class YouTubeKind { VIDEO, PLAYLIST, CHANNEL }

data class YouTubeResult(
    val url: String,
    val title: String,
    val uploader: String,
    val kind: YouTubeKind,
    val durationMs: Long = 0,
    val thumbnail: String = "",
    val viewCount: Long = -1,
    val itemCount: Long = -1,
    val live: Boolean = false,
) {
    val videoId get() = youtubeVideoId(url).orEmpty()
}

data class YouTubePage(val title: String, val subtitle: String, val thumbnail: String, val kind: YouTubeKind, val items: List<YouTubeResult>)

/** Ceiling compared with the audio bitrate in kb/s, for both playback and downloads. */
enum class AudioQuality(val label: String, val ceilingKbps: Int) {
    ECONOMY("Économique", 64), STANDARD("Standard", 128), BEST("Maximale", 10_000);

    val details get() = when (this) {
        ECONOMY -> "Environ 30 Mo par heure. Pour un forfait limité."
        STANDARD -> "Environ 60 Mo par heure. Bon compromis."
        BEST -> "La meilleure piste proposée par YouTube, souvent 160 kb/s."
    }
}

enum class VideoQuality(val label: String, val maxHeight: Int) {
    SD("480p", 480), HD("720p", 720), FHD("1080p", 1080), MAX("Maximale", 4320)
}

private const val ID_CHARS = "[A-Za-z0-9_-]"

private val LINK = Regex("""(?:https?://)?(?:[a-z0-9-]+\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/\S*""", RegexOption.IGNORE_CASE)
private val VIDEO_PATTERNS = listOf(
    Regex("""youtu\.be/($ID_CHARS{11})"""),
    Regex("""[?&]v=($ID_CHARS{11})"""),
    Regex("""/(?:shorts|embed|live|v)/($ID_CHARS{11})"""),
)
private val PLAYLIST = Regex("""[?&]list=($ID_CHARS+)""")
private val CHANNEL = Regex("""youtube\.com/(@[A-Za-z0-9._-]+|channel/UC$ID_CHARS+|c/[A-Za-z0-9._-]+|user/[A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

/** The first YouTube link inside any text, so a share from the YouTube app ("Regarde ca : https://...") works. */
fun youtubeLinkIn(text: String): String? = LINK.find(text.trim())?.value?.trimEnd('.', ',', ')', ']', '"')

fun isYouTubeLink(text: String): Boolean = youtubeLinkIn(text) != null

fun youtubeVideoId(text: String): String? {
    val link = youtubeLinkIn(text) ?: return null
    return VIDEO_PATTERNS.firstNotNullOfOrNull { it.find(link)?.groupValues?.get(1) }
}

/** "RD..." ids are the endless mix YouTube builds around one video, not a playlist anyone published. */
fun youtubePlaylistId(text: String): String? {
    val link = youtubeLinkIn(text) ?: return null
    return PLAYLIST.find(link)?.groupValues?.get(1)?.takeUnless { it.startsWith("RD") || it == "WL" || it == "LL" }
}

fun youtubeChannelUrl(text: String): String? {
    val link = youtubeLinkIn(text) ?: return null
    if (youtubeVideoId(link) != null) return null
    return CHANNEL.find(link)?.let { "https://www.youtube.com/" + it.groupValues[1] }
}

fun youtubeWatchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
fun youtubePlaylistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"
fun youtubeThumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

// Promo tags uploaders add. "(Remix)", "(Live)", "(Acoustic)" and "(feat. ...)" carry meaning and are kept.
private val NOISE = listOf(
    "official music video", "official video", "official audio", "official lyric video", "official lyrics video",
    "official visualizer", "official hd video", "official version", "official", "music video", "lyric video",
    "lyrics video", "lyrics", "letra", "paroles", "clip officiel", "clip video officiel", "vidéo officielle",
    "video officielle", "audio officiel", "visualizer", "visualiser", "audio", "hd", "hq",
    "full hd", "4k", "8k", "mv", "m/v", "high quality", "with lyrics"
)
private val BRACKETS = Regex("""[(\[]([^()\[\]]*)[)\]]""")
private val TRAILING_NOISE = Regex("""\s*[-|–—·]\s*(?:""" + NOISE.joinToString("|") { Regex.escape(it) } + """)\s*$""", RegexOption.IGNORE_CASE)

fun cleanYouTubeTitle(title: String): String {
    var text = title.replace(' ', ' ')
    text = BRACKETS.replace(text) { match ->
        val inner = match.groupValues[1].trim().lowercase().trim('.', '!', '-', '·', ' ')
        if (NOISE.contains(inner)) "" else match.value
    }
    text = TRAILING_NOISE.replace(text, "")
    return text.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', '·', '–', '—').trim()
}

/** "Artiste - Topic" is YouTube's auto-generated music channel; "ArtisteVEVO" its label channel. */
fun cleanChannelName(name: String): String {
    var text = name.trim()
    if (text.endsWith(" - Topic", ignoreCase = true)) text = text.dropLast(8).trim()
    if (text.length > 4 && text.endsWith("VEVO")) text = text.dropLast(4).trim()
    return text
}

private val SEPARATORS = listOf(" - ", " – ", " — ", " ‒ ", " − ")

/** Splits "Artiste - Titre" the way music uploads are named, falling back to the channel name. */
fun youtubeArtistTitle(rawTitle: String, uploader: String): Pair<String, String> {
    val channel = cleanChannelName(uploader)
    val clean = cleanYouTubeTitle(rawTitle)
    val separator = SEPARATORS.firstOrNull { it in clean }
    if (separator != null) {
        val artist = clean.substringBefore(separator).trim()
        val title = clean.substringAfter(separator).trim()
        if (artist.isNotBlank() && title.isNotBlank()) return artist to title
    }
    return channel to clean
}

fun formatViews(count: Long): String = when {
    count < 0 -> ""
    count >= 1_000_000_000 -> String.format(Locale.FRANCE, "%.1f Md de vues", count / 1_000_000_000.0)
    count >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f M de vues", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.FRANCE, "%.0f k vues", count / 1_000.0)
    count <= 1 -> "$count vue"
    else -> "$count vues"
}

fun formatSubscribers(count: Long): String = when {
    count < 0 -> ""
    count >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f M d’abonnés", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.FRANCE, "%.0f k abonnés", count / 1_000.0)
    count <= 1 -> "$count abonné"
    else -> "$count abonnés"
}

/** A stream bitrate reaches us either in kb/s (from the itag table) or in bit/s (from YouTube's own figure). */
fun normalizeKbps(raw: Int): Int = when {
    raw <= 0 -> 0
    raw > 4_000 -> raw / 1000
    else -> raw
}

/** Drops the playback marker and any playlist or timestamp parameter, leaving the plain watch URL. */
fun cleanWatchUrl(uri: String): String = youtubeVideoId(uri)?.let { youtubeWatchUrl(it) } ?: uri

/** Highest bitrate at or under the ceiling; when every track exceeds it, the least expensive one. */
fun pickBitrate(available: List<Int>, ceilingKbps: Int): Int? {
    if (available.isEmpty()) return null
    return available.filter { it in 1..ceilingKbps }.maxOrNull() ?: available.filter { it > 0 }.minOrNull() ?: available.first()
}

fun audioFormatSelector(quality: AudioQuality): String =
    if (quality.ceilingKbps >= 10_000) "bestaudio/best"
    else "bestaudio[abr<=${quality.ceilingKbps}]/bestaudio/best"

fun videoFormatSelector(quality: VideoQuality): String =
    if (quality.maxHeight >= 4320) "bestvideo+bestaudio/best"
    else "bestvideo[height<=${quality.maxHeight}]+bestaudio/best[height<=${quality.maxHeight}]/best"

/**
 * The library keeps the stable watch URL as its identifier (history, cue points, covers).
 * Playback needs a marker so the resolver knows which track of the video to fetch.
 */
fun youtubePlaybackUri(watchUrl: String, video: Boolean): String =
    watchUrl + (if ('?' in watchUrl) "&" else "?") + "echo=" + (if (video) "video" else "audio")

fun wantsVideoStream(uri: String) = "echo=video" in uri

/** Most recent first, no repeats, oldest dropped past [max]. */
fun pushRecent(existing: List<String>, term: String, max: Int = 6): List<String> {
    val clean = term.trim()
    if (clean.isBlank()) return existing
    return (listOf(clean) + existing).distinctBy { it.lowercase() }.take(max)
}

fun youtubeFileBase(artist: String, title: String): String =
    safeFileName(if (artist.isBlank()) title else "$artist - $title")
