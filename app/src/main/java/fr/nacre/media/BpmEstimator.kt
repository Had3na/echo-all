package fr.nacre.media

import kotlin.math.*
import kotlin.math.roundToInt
import kotlin.random.Random

data class TempoEstimate(val bpm: Float, val confidence: Float)

fun estimateTempo(energy: List<Float>, rate: Int = 100): TempoEstimate {
    if (energy.size < rate * 8 || energy.maxOrNull().let { it == null || it < .0001f }) return TempoEstimate(0f, 0f)
    val onset = FloatArray(energy.size) { i -> if (i == 0) 0f else (energy[i] - energy[i - 1]).coerceAtLeast(0f) }
    var best = 0.0; var bestLag = 0
    for (lag in (rate * 60 / 200)..(rate * 60 / 60)) {
        var numerator = 0.0; var left = 0.0; var right = 0.0
        for (i in lag until onset.size) {
            numerator += onset[i] * onset[i - lag]
            left += onset[i] * onset[i]; right += onset[i - lag] * onset[i - lag]
        }
        val score = numerator / sqrt(left * right + 1e-12)
        // Prefer the faster fundamental when harmonics have essentially equal scores.
        if (score > best + .005) { best = score; bestLag = lag }
    }
    return if (bestLag == 0 || best < .08) TempoEstimate(0f, best.toFloat())
        else TempoEstimate(60f * rate / bestLag, best.toFloat().coerceIn(0f, 1f))
}

/**
 * Where the beat grid starts, in milliseconds, read from the same envelope [estimateTempo] works on.
 *
 * A comb: of every offset inside one beat, the one kept is the one whose beat positions collect the
 * most attack energy. Same reasoning as the tempo search, one step further. The resolution is the
 * envelope's own, 10 ms, which is well under what an ear hears as two drums side by side.
 */
fun beatPhaseMs(energy: List<Float>, bpm: Float, rate: Int = 100): Long {
    if (bpm !in 40f..240f || energy.size < rate * 4) return 0
    val period = rate * 60f / bpm
    val span = period.toInt()
    if (span < 2) return 0
    val onset = FloatArray(energy.size) { i -> if (i == 0) 0f else (energy[i] - energy[i - 1]).coerceAtLeast(0f) }
    var best = -1.0
    var bestOffset = 0
    for (offset in 0 until span) {
        var total = 0.0
        var beat = 0
        while (true) {
            val index = (offset + beat * period).toInt()
            if (index >= onset.size) break
            total += onset[index]
            beat++
        }
        if (total > best) { best = total; bestOffset = offset }
    }
    return bestOffset * 1000L / rate
}

/** Positive remainder, so a beat grid can be walked backwards without falling off it. */
private fun wrap(value: Float, span: Float) = ((value % span) + span) % span

/**
 * Mix length rounded to whole beats, so a transition ends with the music instead of across it.
 * Groups of four are preferred when one is within a beat of what was asked; an unknown tempo
 * leaves the request untouched.
 */
fun snapMixToBeats(requestedMs: Long, bpm: Float, speed: Float = 1f): Long {
    if (bpm !in 40f..240f || requestedMs <= 0 || speed <= .1f) return requestedMs
    val beat = 60_000f / bpm / speed
    val beats = requestedMs / beat
    if (beats < 1f) return requestedMs
    val bar = (beats / 4f).roundToInt().coerceAtLeast(1) * 4
    val choice = if (abs(bar - beats) <= 1f) bar else beats.roundToInt().coerceAtLeast(1)
    return (choice * beat).toLong()
}

/**
 * Where to start the arriving track so its beats land on the leaving one's.
 *
 * Both grids are brought into real time, since playback speed stretches a track's own timeline, and
 * the arriving one is then moved to the nearest position whose beats coincide — at most half a beat
 * from [preferredMs]. Matching the tempo without this is two tracks at the same speed whose drums
 * fall beside each other. An unknown tempo on either side leaves [preferredMs] alone.
 */
fun alignedStart(preferredMs: Long,
                 incomingBeatMs: Long, incomingBpm: Float, incomingSpeed: Float,
                 outgoingPositionMs: Long, outgoingBeatMs: Long, outgoingBpm: Float, outgoingSpeed: Float): Long {
    if (incomingBpm !in 40f..240f || outgoingBpm !in 40f..240f) return preferredMs
    if (incomingSpeed <= .1f || outgoingSpeed <= .1f) return preferredMs
    val periodOut = 60_000f / outgoingBpm
    val periodIn = 60_000f / incomingBpm
    // Real time left before the leaving track's next beat.
    val wait = (periodOut - wrap((outgoingPositionMs - outgoingBeatMs).toFloat(), periodOut)) / outgoingSpeed
    // The arriving track has to sit exactly that far before one of its own beats.
    val target = wrap(incomingBeatMs + wrap(periodIn - wait * incomingSpeed, periodIn), periodIn)
    val steps = ((preferredMs - target) / periodIn).roundToInt()
    return (target + steps * periodIn).toLong().coerceAtLeast(0)
}

fun tappedBpm(taps: List<Long>): Float {
    val intervals = taps.zipWithNext { a, b -> b - a }.takeLast(7)
    if (intervals.size < 3 || intervals.any { it !in 250..1500 }) return 0f
    val sorted = intervals.sorted()
    return 60_000f / sorted[sorted.size / 2]
}

data class TrackProfile(val uri: String, val bpm: Float, val artist: String, val genre: String = "")

/** A tempo nobody has measured yet: far enough not to be chosen first, near enough not to be exiled. */
private const val UNKNOWN_TEMPO_COST = 12f
private const val SAME_ARTIST_COST = 30f
private const val OTHER_GENRE_COST = 8f

/**
 * What it costs to follow [from] with [to]: the tempo step above all, plus a push away from playing
 * the same artist twice in a row and a mild pull towards staying in the same genre.
 */
fun transitionCost(from: TrackProfile, to: TrackProfile): Float {
    val tempo = if (from.bpm <= 0f || to.bpm <= 0f) UNKNOWN_TEMPO_COST else abs(from.bpm - to.bpm)
    val artist = if (from.artist.isNotBlank() && from.artist.equals(to.artist, true)) SAME_ARTIST_COST else 0f
    val genre = if (from.genre.isNotBlank() && to.genre.isNotBlank() && !from.genre.equals(to.genre, true)) OTHER_GENRE_COST else 0f
    return tempo + artist + genre
}

/**
 * Orders a selection so every track follows the one that fits it best rather than a random one.
 * A little noise keeps two runs over the same album from giving the same order, and [seed] makes
 * that noise reproducible. Tracks whose tempo is unknown still take part: the result simply
 * degrades towards plain shuffling instead of refusing to work.
 */
fun smartOrder(tracks: List<TrackProfile>, first: TrackProfile? = null, seed: Long = 0): List<TrackProfile> {
    if (tracks.size <= 2) return tracks
    val random = Random(seed)
    val pool = tracks.toMutableList()
    val start = first?.let { head -> pool.firstOrNull { it.uri == head.uri } } ?: pool[random.nextInt(pool.size)]
    pool.remove(start)
    val ordered = mutableListOf(start)
    while (pool.isNotEmpty()) {
        val previous = ordered.last()
        val next = pool.minByOrNull { transitionCost(previous, it) + random.nextFloat() * 4f } ?: break
        pool.remove(next)
        ordered += next
    }
    return ordered
}

/**
 * How far a track may be pushed from the tempo it was recorded at. A quarter faster or slower, as
 * this used to allow, is not beat matching: it is a different piece of music. Six percent is what
 * a pitch fader is normally worked within, and it stays out of the way of the sound.
 */
const val MAX_TEMPO_STRETCH = .06f

/**
 * Speed for the arriving track so its pulse lands on the leaving one's, or null when that cannot be
 * had without spoiling it.
 *
 * Half and double time count as a match: a 90 BPM track arriving under a 178 BPM one wants a one
 * percent nudge counted at double time, not a doubling.
 *
 * The limit is measured against [outgoingSpeed], the speed already being heard, and not against 1.
 * A listener who set 1.1x wants the next track at 1.1x too; only the matching adjustment on top of
 * their choice is what has to stay small.
 */
fun matchedSpeed(outgoingBpm: Float, outgoingSpeed: Float, incomingBpm: Float): Float? {
    if (outgoingBpm !in 40f..240f || incomingBpm !in 40f..240f) return null
    if (outgoingSpeed !in .5f..2f) return null
    val target = outgoingBpm * outgoingSpeed
    val speed = listOf(incomingBpm, incomingBpm * 2f, incomingBpm / 2f)
        .map { target / it }
        .minByOrNull { abs(it - outgoingSpeed) } ?: return null
    return speed.takeIf { abs(it / outgoingSpeed - 1f) <= MAX_TEMPO_STRETCH && it in .5f..2f }
}

fun shapedMix(progress: Float, style: String): MixGains {
    val p = progress.coerceIn(0f, 1f)
    // The style shapes when the blend moves; the gain law then keeps the loudness steady throughout.
    val blend = when (style) { "cut" -> if (p < .5f) 0f else 1f; "smooth", "club", "sweep" -> p*p*(3-2*p); else -> p }
    return equalPower(blend)
}
