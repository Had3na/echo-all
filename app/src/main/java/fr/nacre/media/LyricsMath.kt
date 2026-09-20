package fr.nacre.media

import java.text.Normalizer
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)
fun normalizedMusic(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Require an exact title/artist and close duration; never guess from a popular title alone. */
fun automaticTag(title: String, artist: String, duration: Long, candidates: List<TagCandidate>): TagCandidate? {
    if (artist.isBlank() || duration <= 0) return null
    val matches = candidates.filter { it.score >= 98 && normalizedMusic(it.title) == normalizedMusic(title) && normalizedMusic(it.artist) == normalizedMusic(artist) && it.lengthMs > 0 && abs(it.lengthMs - duration) <= 3000 }
    // Different releases of the same song can disagree: leave those choices to manual lookup.
    return matches.firstOrNull()?.takeIf { matches.map { m -> m.album }.distinct().size == 1 }
}

/** Supports repeated timestamps, fractions, CRLF and the LRC offset header. */
fun parseLrc(value: String): List<LyricLine> {
    val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60000, 60000) ?: 0L
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    return value.lineSequence().flatMap { line ->
        val times = stamp.findAll(line).toList()
        val text = line.substring(times.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
        times.mapNotNull { m ->
            val sec = m.groupValues[2].toLong(); if(sec >= 60) null else {
                val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLong()
                LyricLine((m.groupValues[1].toLong()*60000 + sec*1000 + fraction + offset).coerceAtLeast(0), text)
            }
        }.asSequence()
    }.take(10000).sortedBy { it.timeMs }.toList()
}

fun activeLyric(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0; var high = lines.size
    while(low < high) { val mid = (low + high)/2; if(lines[mid].timeMs <= positionMs) low = mid + 1 else high = mid }
    return low - 1
}
