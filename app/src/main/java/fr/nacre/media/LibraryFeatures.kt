package fr.nacre.media

import org.json.JSONObject

data class MusicGroup(val key: String, val title: String, val subtitle: String, val tracks: List<LibraryItem>)
fun musicGroups(items: List<LibraryItem>, artists: Boolean): List<MusicGroup> = items.filter { it.kind == MediaKind.MUSIC && it.source != "Radio" }.groupBy {
    normalizedMusic(it.artist.ifBlank { "Artiste inconnu" }) + if(artists) "" else "\u0000" + normalizedMusic(it.album.ifBlank { "Sans album" })
}.map { (key, tracks) ->
    val first = tracks.first()
    MusicGroup(key, if(artists) first.artist.ifBlank { "Artiste inconnu" } else first.album.ifBlank { "Sans album" },
        if(artists) "${tracks.size} morceaux · ${tracks.map { normalizedMusic(it.album) }.distinct().size} albums" else first.artist.ifBlank { "Artiste inconnu" }, tracks.sortedBy { normalizedMusic(it.title) })
}.sortedBy { normalizedMusic(it.title) }

fun mediaClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if(seconds >= 3600) "%d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60) else "%d:%02d".format(seconds/60, seconds%60)
}
fun videoResumePosition(position: Long, duration: Long): Long {
    if(duration <= 0 || position < 1000) return 0
    val endMargin = (duration / 20).coerceIn(1000, 10000)
    return position.takeIf { it < duration - endMargin } ?: 0
}

/** Only the metadata fields are restored: favorites, categories and other changes remain intact. */
fun metadataSnapshot(item: LibraryItem): String = JSONObject().put("title", item.title).put("artist", item.artist).put("album", item.album).put("tagged", item.tagged).toString()
fun applyAutomaticTags(item: LibraryItem, raw: String): LibraryItem {
    val data = JSONObject(raw)
    if(item.tagged || item.autoMetadataBlocked || data.optBoolean("manual") || data.optBoolean("blocked")) return item
    return item.copy(title = data.getString("title"), artist = data.getString("artist"), album = data.optString("album"), tagged = true,
        metadataUndo = item.metadataUndo.ifBlank { metadataSnapshot(item) })
}
fun undoAutomaticTags(item: LibraryItem): LibraryItem {
    if(item.metadataUndo.isBlank()) return item
    val data = JSONObject(item.metadataUndo)
    return item.copy(title = data.getString("title"), artist = data.getString("artist"), album = data.getString("album"), tagged = data.optBoolean("tagged"), metadataUndo = "", autoMetadataBlocked = true)
}
