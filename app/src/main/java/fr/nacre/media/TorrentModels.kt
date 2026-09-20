package fr.nacre.media

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal const val MAX_TORRENT_BYTES = 4_000_000
internal enum class TorrentState { QUEUED, METADATA, CHECKING, RUNNING, STOPPING, PAUSED, DONE, FAILED }
internal data class TorrentJob(
    val id: String, val title: String, val magnet: String = "", val state: TorrentState = TorrentState.QUEUED,
    val downloaded: Long = 0, val total: Long = 0, val speed: Long = 0, val peers: Int = 0,
    val error: String = "", val files: List<String> = emptyList()
) {
    val active get() = state in listOf(TorrentState.QUEUED, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.RUNNING)
}
internal fun torrentMagnet(value: String): String {
    val text = value.trim()
    require(text.length <= 16_384 && text.none { it.isISOControl() }) { "Lien magnet invalide." }
    val uri = URI(text)
    require(uri.scheme.equals("magnet", true)) { "Colle un lien commençant par magnet:?xt=…" }
    val parts = text.substringAfter('?', "").split('&').map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    require(parts.any { (key, value) -> key == "xt" && (
        value.matches(Regex("urn:btih:([0-9a-fA-F]{40}|[a-zA-Z2-7]{32})")) ||
        value.matches(Regex("urn:btmh:1220[0-9a-fA-F]{64}"))) }) { "Le magnet ne contient pas une empreinte BitTorrent valide." }
    return text
}
internal fun torrentDisplayName(magnet: String): String = runCatching {
    magnet.substringAfter('?').split('&').firstOrNull { it.startsWith("dn=") }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }?.take(200)
}.getOrNull().orEmpty().ifBlank { "Torrent magnet" }

/** Used for torrent metadata, exported files and FileProvider URIs alike. */
internal fun torrentChild(root: File, relative: String): File {
    val path = relative.replace('\\', '/')
    require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && !path.contains(':') && !path.contains('\u0000')) { "Chemin torrent invalide." }
    require(path.split('/').none { it == ".." || it == "." || it.isBlank() }) { "Chemin torrent invalide." }
    val base = root.canonicalFile
    val file = File(base, path).canonicalFile
    require(file.toPath().startsWith(base.toPath()) && file != base) { "Le fichier sort du dossier torrent." }
    return file
}
internal fun torrentMime(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "ts" -> "video/mp2t"
    "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a", "aac" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
    "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"; "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}
