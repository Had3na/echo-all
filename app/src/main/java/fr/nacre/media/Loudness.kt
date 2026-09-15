package fr.nacre.media

import kotlin.math.exp
import kotlin.math.log10

/** Level every track is brought to. -11 LUFS keeps phone speakers loud while evening out tracks. */
const val TARGET_LUFS = -11.0
private const val REPLAY_GAIN_REFERENCE_LUFS = -18.0

/**
 * Integrated loudness (LUFS) after ITU-R BS.1770: K-weighting, 400 ms blocks, absolute and relative gates.
 * Blocks do not overlap (the standard uses 75 %), which changes music results by a few hundredths of a dB.
 */
class LoudnessMeter(val rate: Int, channels: Int) {
    private val shelf = Biquad(channels).apply { highShelf(1681.974450955533, 3.999843853973347, .7071752369554196, rate) }
    private val highPass = Biquad(channels).apply { highPass(38.13547087602444, .5003270373238773, rate) }
    private val blockFrames = (rate * 4 / 10).coerceAtLeast(1)
    private var energy = 0.0
    private var frames = 0
    private val blocks = ArrayList<Double>()

    val seconds get() = blocks.size * .4

    fun add(sample: Double, channel: Int) {
        val k = highPass.process(shelf.process(sample, channel), channel)
        energy += k * k
    }
    fun endFrame() {
        if (++frames < blockFrames) return
        if (blocks.size < 30_000) blocks += energy / frames // about 3 h 20 min
        energy = 0.0; frames = 0
    }

    private fun lufs(meanSquare: Double) = -.691 + 10 * log10(meanSquare)

    /** Null while nothing above the -70 LUFS silence gate has been heard. */
    fun integrated(): Double? {
        val audible = blocks.filter { it > 0 && lufs(it) > -70 }
        if (audible.isEmpty()) return null
        val relative = lufs(audible.average()) - 10
        val gated = audible.filter { lufs(it) > relative }
        return lufs((gated.ifEmpty { audible }).average())
    }
}

/** Peak limiter: instant attack, 150 ms release. Output never exceeds [ceiling]; quiet audio passes unchanged. */
class Limiter(rate: Int, private val ceiling: Double = .97) {
    private val release = exp(-1.0 / (.15 * rate))
    private var gain = 1.0
    fun gainFor(peak: Double): Double {
        val target = if (peak > ceiling) ceiling / peak else 1.0
        gain = if (target < gain) target else target + (gain - target) * release
        return gain
    }
}

/** dB to apply to a track: its ReplayGain tag when present, else the loudness measured on earlier plays, else nothing. */
fun normalizationDb(replayGainDb: Float?, measuredLufs: Double?): Float = when {
    replayGainDb != null -> replayGainDb + (TARGET_LUFS - REPLAY_GAIN_REFERENCE_LUFS).toFloat()
    measuredLufs != null -> (TARGET_LUFS - measuredLufs).toFloat()
    else -> 0f
}.coerceIn(-12f, 6f)

/** Reads "REPLAYGAIN_TRACK_GAIN" from ID3 (TXXX) or Vorbis comment metadata entries, given as their text form. */
fun replayGainDb(entries: List<String>): Float? = entries.firstNotNullOfOrNull { entry ->
    Regex("REPLAYGAIN_TRACK_GAIN\\D*?([-+]?\\d+(?:[.,]\\d+)?)\\s*dB", RegexOption.IGNORE_CASE).find(entry)
        ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in -30f..30f }
}
