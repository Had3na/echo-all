package fr.nacre.media

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class TorrentHit(val id: String, val title: String, val source: String, val magnet: String = "",
    val url: String = "", val archiveId: String = "", val seeders: Long? = null, val bytes: Long? = null)
internal data class TorrentSearchResult(val hits: List<TorrentHit>, val errors: List<String>)
internal fun torrentWords(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
internal fun torrentMatch(query: String, title: String): Int {
    val q = torrentWords(query); val t = torrentWords(title)
    if (q.length < 2) return 0
    val extras = setOf("trailer", "teaser", "sample", "review", "bande annonce", "soundtrack", "ost", "compilation")
    if (extras.any { " $it " in " $t " && " $it " !in " $q " }) return 0
    val suffix = t.removePrefix("$q ")
    val release = Regex("^(?:[0-9]{4}|480p|576p|720p|1080p|1080i|2160p|4k|bluray|brrip|bdrip|dvdrip|web|webrip|webdl|hdtv|x264|x265|h264|h265|hevc|av1|vf|vff|vostfr|multi|french|english|s[0-9]{1,2}(?:e[0-9]{1,3})?|season|saison|complete|complet|integrale)(?: |$)")
    return when { t == q -> 4; t.startsWith("$q ") && release.containsMatchIn(suffix) -> 3; " $q " in " $t " -> 2; else -> 0 }
}
internal fun rankTorrentHits(query: String, hits: List<TorrentHit>): List<TorrentHit> = hits
    .sortedWith(compareByDescending<TorrentHit> { torrentMatch(query, it.title) }.thenByDescending { it.seeders ?: -1L })
    .distinctBy { if (it.magnet.isNotBlank()) torrentIdentity(it.magnet) else it.id }
internal fun automaticTorrent(query: String, hits: List<TorrentHit>): TorrentHit? =
    rankTorrentHits(query, hits).firstOrNull { torrentMatch(query, it.title) >= 3 && it.seeders != 0L }
internal fun torrentIdentity(reference: String): String {
    val canonical = if (reference.startsWith("magnet:", true)) reference.substringAfter('?').split('&')
        .firstOrNull { it.startsWith("xt=") }?.let { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8").lowercase(Locale.ROOT) } ?: reference else reference
    return UUID.nameUUIDFromBytes(canonical.toByteArray(Charsets.UTF_8)).toString()
}
internal fun parsePirateBay(array: JSONArray): List<TorrentHit> = (0 until array.length()).mapNotNull { i ->
    val j = array.optJSONObject(i) ?: return@mapNotNull null
    val hash = j.optString("info_hash")
    val title = j.optString("name").trim()
    if (j.optString("id") == "0" || title.isBlank() || !hash.matches(Regex("[a-fA-F0-9]{40}")) || j.optInt("category") !in 200..299) return@mapNotNull null
    val magnet = "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(title, "UTF-8")
    TorrentHit("tpb:${hash.lowercase(Locale.ROOT)}", title, "The Pirate Bay", magnet,
        seeders = j.optLong("seeders", 0).coerceAtLeast(0), bytes = j.optLong("size").takeIf { it > 0 })
}
internal fun archiveTorrentQuery(text: String): String {
    val q = text.trim().take(200).replace("\\", "\\\\").replace("\"", "\\\"")
    require(torrentWords(q).length >= 2) { "Saisis au moins deux caractères." }
    return "title:(\"$q\") AND mediatype:movies AND format:\"Archive BitTorrent\" AND -access-restricted-item:true"
}
internal fun parseArchiveTorrents(root: JSONObject): List<TorrentHit> = root.optJSONObject("response")?.optJSONArray("docs").cinemaObjects().mapNotNull { j ->
    val id = j.cinemaText("identifier"); val title = j.cinemaText("title")
    if (!id.matches(Regex("[A-Za-z0-9_.-]+")) || title.isBlank()) null else TorrentHit("archive:$id", title, "Internet Archive", archiveId = id)
}
internal fun parseTorznab(xml: String, source: String): List<TorrentHit> {
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Réponse XML non prise en charge." }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
    val document = builder.parse(xml.byteInputStream())
    require(document.documentElement.localName != "error") { "Le serveur Torznab a refusé la recherche. Vérifie sa clé API." }
    val items = document.getElementsByTagName("item")
    return (0 until items.length.coerceAtMost(100)).mapNotNull { index ->
        val item = items.item(index) as? Element ?: return@mapNotNull null
        fun text(name: String) = item.getElementsByTagName(name).item(0)?.textContent.orEmpty().trim()
        val attrs = item.getElementsByTagNameNS("*", "attr")
        fun attr(name: String): String = (0 until attrs.length).mapNotNull { attrs.item(it) as? Element }
            .firstOrNull { it.getAttribute("name") == name }?.getAttribute("value").orEmpty()
        val title = text("title")
        val enclosure = item.getElementsByTagName("enclosure").item(0) as? Element
        val reference = attr("magneturl").ifBlank { enclosure?.getAttribute("url").orEmpty().ifBlank { text("link") } }
        val magnet = runCatching { torrentMagnet(reference) }.getOrDefault("")
        val url = if (magnet.isEmpty() && validStreamUrl(reference)) reference else ""
        if (title.isBlank() || (magnet.isBlank() && url.isBlank())) null else
            TorrentHit(torrentIdentity(reference), title, source, magnet, url, seeders = attr("seeders").toLongOrNull(),
                bytes = attr("size").toLongOrNull() ?: enclosure?.getAttribute("length")?.toLongOrNull())
    }
}

internal fun parseYts(root: JSONObject): List<TorrentHit> {
    require(root.cinemaText("status") == "ok") { "YTS n’a pas accepté la recherche." }
    val data = requireNotNull(root.optJSONObject("data")) { "Réponse YTS invalide." }
    return data.optJSONArray("movies").cinemaObjects().take(50).flatMap { movie ->
        val name = movie.cinemaText("title")
        if (name.isBlank()) return@flatMap emptyList()
        val title = movie.cinemaText("title_long").ifBlank {
            name + movie.optInt("year").takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
        }
        movie.optJSONArray("torrents").cinemaObjects().take(20).mapNotNull { torrent ->
            val hash = torrent.cinemaText("hash")
            if (!hash.matches(Regex("[a-fA-F0-9]{40}"))) return@mapNotNull null
            val label = listOf(title, torrent.cinemaText("quality"), torrent.cinemaText("type"),
                movie.cinemaText("language")).filter { it.isNotBlank() }.joinToString(" ")
            TorrentHit("yts:${hash.lowercase(Locale.ROOT)}", label, "YTS",
                magnet = "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(label, "UTF-8"),
                seeders = torrent.optLong("seeds", 0).coerceAtLeast(0),
                bytes = torrent.optLong("size_bytes", 0).takeIf { it > 0 })
        }
    }
}
