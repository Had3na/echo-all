package fr.nacre.media

import kotlin.math.sqrt

data class MixGains(val outgoing: Float, val incoming: Float) {
    /** 1 when the pair keeps the loudness a single deck had. */
    val power get() = outgoing * outgoing + incoming * incoming
}

/**
 * Equal power, not equal amplitude.
 *
 * Half way through a fade both decks sit at 0.707 rather than 0.5. Two unrelated tracks add up in
 * power, not in amplitude, so a constant-sum fade loses about 3 dB right in the middle: that is the
 * audible hole a crossfade is meant not to have. The price is that two nearly identical tracks can
 * peak roughly 3 dB higher while they overlap, which only happens when the same song follows itself.
 */
fun equalPower(blend: Float): MixGains {
    val b = blend.coerceIn(0f, 1f)
    return MixGains(sqrt(1f - b), sqrt(b))
}

/** Gains at one instant of a plain fade of [durationMs]. */
fun mixGains(elapsedMs: Long, durationMs: Long): MixGains =
    equalPower(if (durationMs <= 0) 1f else elapsedMs.toFloat() / durationMs)
