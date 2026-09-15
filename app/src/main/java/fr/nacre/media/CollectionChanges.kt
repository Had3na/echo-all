package fr.nacre.media

import org.json.JSONArray
import org.json.JSONObject

/** Deduplicate references without reordering and keep the transition length in range. */
fun SavedList.normalized() = copy(uris = uris.distinct(), seconds = seconds.coerceIn(0, 60), style = style.takeIf { it in MIX_STYLES } ?: "smooth")

val MIX_STYLES = listOf("smooth", "linear", "cut", "club", "sweep")

fun <T> collectionOrder(items: List<T>, shuffle: Boolean, random: kotlin.random.Random = kotlin.random.Random.Default): List<T> =
    if (shuffle) items.shuffled(random) else items.toList()

data class PadSlot(val uri: String, val pad: Int)
data class PlayStat(val count: Int, val last: Long)
data class StudioSnapshot(
    val lists: List<SavedList> = emptyList(),
    val tracks: Map<String, TrackTools> = emptyMap(),
    val marks: Map<String, List<Long>> = emptyMap(),
    val pads: Map<PadSlot, Long> = emptyMap(),
    val plays: Map<String, PlayStat> = emptyMap(),
    /** Cover key → stored file path, legacy content:// reference, or data:image/… inside a backup. */
    val covers: Map<String, String> = emptyMap()
)

fun encodeWave(wave: List<Float>) = wave.joinToString(",")
fun decodeWave(text: String): List<Float> = if (text.isBlank()) emptyList() else text.split(',').mapNotNull { it.toFloatOrNull() }

/**
 * Reads the key/value layout used by the old "studio" preferences and by backup files
 * ("lists", "track:<uri>", "marks:<uri>", "pad:<uri>:<n>", "count:<uri>", "last:<uri>", "art:<key>").
 * strict = true (backup import) rejects the whole file on invalid playlists or tracks;
 * strict = false (migration of the phone's own data) skips damaged entries instead of losing everything.
 */
fun parseStudio(entries: Map<String, Any?>, strict: Boolean): StudioSnapshot {
    val lists = mutableListOf<SavedList>()
    val tracks = HashMap<String, TrackTools>()
    val marks = HashMap<String, List<Long>>()
    val pads = HashMap<PadSlot, Long>()
    val counts = HashMap<String, Int>()
    val lasts = HashMap<String, Long>()
    val covers = HashMap<String, String>()
    fun guarded(block: () -> Unit) { if (strict) block() else runCatching(block) }
    for ((key, value) in entries) when {
        key == "lists" && value is String -> guarded {
            val array = JSONArray(value)
            require(!strict || array.length() <= 500)
            for (i in 0 until array.length()) guarded {
                val j = array.getJSONObject(i)
                val uris = j.getJSONArray("uris")
                if (strict) { require(j.getString("id").length <= 100); require(j.getString("name").length <= 500); require(uris.length() <= 20_000); require(j.optInt("seconds", 5) in 0..60) }
                lists += SavedList(j.getString("id"), j.getString("name"), List(uris.length()) { uris.getString(it) }, j.optString("style", "smooth"), j.optInt("seconds", 5), j.optBoolean("shuffle", false)).normalized()
            }
        }
        key.startsWith("track:") && value is String -> guarded {
            val t = JSONObject(value)
            val wave = t.optJSONArray("wave") ?: JSONArray()
            val tools = TrackTools(t.optDouble("bpm", 0.0).toFloat(), t.optDouble("confidence", 0.0).toFloat(), t.optLong("in"), t.optLong("out"),
                t.optLong("loopIn"), t.optLong("loopOut"), t.optBoolean("loop"), List(minOf(wave.length(), 128)) { wave.optDouble(it).toFloat() })
            require(tools.bpm in 0f..240f && tools.cueIn >= 0 && (tools.cueOut == 0L || tools.cueOut > tools.cueIn) && wave.length() <= 128)
            tracks[key.removePrefix("track:")] = tools
        }
        key.startsWith("marks:") && value is String -> value.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }.takeIf { it.isNotEmpty() }
            ?.let { marks[key.removePrefix("marks:")] = it.distinct().sorted() }
        key.startsWith("pad:") && value is Number -> {
            val rest = key.removePrefix("pad:")
            val pad = rest.substringAfterLast(':', "").toIntOrNull()
            if (pad != null && pad in 0..3 && value.toLong() >= 0) pads[PadSlot(rest.substringBeforeLast(':'), pad)] = value.toLong()
        }
        key.startsWith("count:") && value is Number -> counts[key.removePrefix("count:")] = value.toInt().coerceAtLeast(0)
        key.startsWith("last:") && value is Number -> lasts[key.removePrefix("last:")] = value.toLong().coerceAtLeast(0)
        key.startsWith("art:") && value is String && (value.startsWith("content://") || value.startsWith("data:image/")) -> covers[key.removePrefix("art:")] = value
    }
    val plays = (counts.keys + lasts.keys).associateWith { PlayStat(counts[it] ?: 0, lasts[it] ?: 0) }
    return StudioSnapshot(lists.distinctBy { it.id }, tracks, marks, pads, plays, covers)
}

/** Same layout as parseStudio, so backups stay readable by every version. */
fun StudioSnapshot.toLegacyJson(coverValue: (String) -> String?): JSONObject = JSONObject().apply {
    put("lists", JSONArray().apply { lists.forEach { list ->
        put(JSONObject().apply { put("id", list.id); put("name", list.name); put("uris", JSONArray(list.uris)); put("style", list.style); put("seconds", list.seconds); put("shuffle", list.shuffle) })
    } }.toString())
    tracks.forEach { (uri, t) -> put("track:$uri", JSONObject().apply {
        put("bpm", t.bpm.toDouble()); put("confidence", t.confidence.toDouble()); put("in", t.cueIn); put("out", t.cueOut)
        put("loopIn", t.loopIn); put("loopOut", t.loopOut); put("loop", t.loop); put("wave", JSONArray().apply { t.wave.forEach { put(it.toDouble()) } })
    }.toString()) }
    marks.forEach { (uri, times) -> put("marks:$uri", times.joinToString(",")) }
    pads.forEach { (slot, time) -> put("pad:${slot.uri}:${slot.pad}", time) }
    plays.forEach { (uri, play) -> put("count:$uri", play.count); put("last:$uri", play.last) }
    covers.forEach { (key, path) -> coverValue(path)?.let { put("art:$key", it) } }
}
