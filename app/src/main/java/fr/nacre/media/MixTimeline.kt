package fr.nacre.media

/** Target volume factor and filters for one deck at one instant of a transition. */
data class DeckMix(val gain: Float, val shape: SoundShape)
data class MixFrame(val progress: Float, val outgoing: DeckMix, val incoming: DeckMix, val done: Boolean)

/** Clock-driven A → B transition. Pure state: PlaybackService applies the frames to the players. */
class MixTimeline {
    var manual = false; private set
    var blend = 0f; private set
    private var from = 0f
    private var start = 0L
    private var length = 1L

    fun begin(now: Long, durationMs: Long, manual: Boolean) {
        this.manual = manual; blend = 0f; from = 0f; start = now; length = durationMs.coerceAtLeast(1)
    }
    fun stop() { manual = false; blend = 0f; from = 0f }
    fun move(value: Float) { blend = value.coerceIn(0f, 1f) }
    /** Hands a manual blend back to the clock: the transition continues from the slider position, without a jump. */
    fun release(now: Long) { if (!manual) return; from = blend; manual = false; start = now }

    fun progress(now: Long): Float = if (manual) blend else (from + (1 - from) * ((now - start).toFloat() / length)).coerceIn(0f, 1f)

    fun frame(now: Long, style: String): MixFrame {
        val p = progress(now)
        val gains = if (manual || from > 0f) equalPower(p) else shapedMix(p, style)
        val done = if (manual) blend >= .999f else now - start >= length
        return MixFrame(p, DeckMix(gains.outgoing, soundShape(p, style, false)), DeckMix(gains.incoming, soundShape(p, style, true)), done)
    }

    companion object {
        /** What the remaining deck gets once a transition ends, whether it finished, was paused or was skipped. */
        val SETTLED = DeckMix(1f, SoundShape.NEUTRAL)
    }
}

/** Mix length limited so A reaches its exit point (cue out or end of file) no later than the fade. */
fun mixWindow(requestedMs: Long, positionMs: Long, cueOutMs: Long, durationMs: Long, speed: Float): Long {
    val window = requestedMs.coerceAtLeast(1)
    val end = if (cueOutMs > positionMs) minOf(cueOutMs, durationMs.takeIf { it > 0 } ?: cueOutMs) else durationMs
    return if (end > 0) minOf(window, ((end - positionMs) / speed).toLong().coerceAtLeast(1)) else window
}

/** Real time left before the track's exit point, at the current speed. Long.MAX_VALUE when unknown. */
fun remainingMs(tools: TrackTools, positionMs: Long, durationMs: Long, speed: Float): Long {
    val end = if (!tools.loop && tools.cueOut > tools.cueIn) minOf(tools.cueOut, durationMs.takeIf { it > 0 } ?: tools.cueOut) else durationMs
    return if (end > 0) ((end - positionMs) / speed).toLong() else Long.MAX_VALUE
}

/** The next track is prepared a few seconds ahead so it is ready when the mix must start. */
fun autoMixDue(remainingMs: Long, seconds: Int) = seconds > 0 && remainingMs in 1..(seconds * 1000L + 3000)
