package fr.nacre.media

import kotlin.math.*

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

fun tappedBpm(taps: List<Long>): Float {
    val intervals = taps.zipWithNext { a, b -> b - a }.takeLast(7)
    if (intervals.size < 3 || intervals.any { it !in 250..1500 }) return 0f
    val sorted = intervals.sorted()
    return 60_000f / sorted[sorted.size / 2]
}

fun matchedSpeed(outgoingBpm: Float, outgoingSpeed: Float, incomingBpm: Float): Float? {
    if (outgoingBpm !in 40f..240f || incomingBpm !in 40f..240f) return null
    val speed = outgoingBpm * outgoingSpeed / incomingBpm
    return speed.takeIf { it in .8f..1.25f }
}

fun shapedMix(progress: Float, style: String): MixGains {
    val p = progress.coerceIn(0f, 1f)
    val blend = when (style) { "cut" -> if (p < .5f) 0f else 1f; "smooth", "club", "sweep" -> p*p*(3-2*p); else -> p }
    return MixGains(1-blend, blend)
}
