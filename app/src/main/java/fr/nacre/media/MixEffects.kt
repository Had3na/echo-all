package fr.nacre.media

import kotlin.math.log10
import kotlin.math.pow

/** Filter targets for one deck during a transition. 0 Hz switches a filter off; effects only ever remove energy. */
data class SoundShape(val bassDb: Float = 0f, val highPassHz: Float = 0f, val lowPassHz: Float = 0f) {
    val neutral get() = bassDb == 0f && highPassHz == 0f && lowPassHz == 0f
    companion object { val NEUTRAL = SoundShape() }
}

const val BASS_KILL_DB = -30f

/**
 * Two tracks at equal level add about 3 dB where their spectra overlap, and the low end is both
 * where the energy sits and where a crossfade turns to mud. Taking 3 dB off each low end at the
 * half-way point cancels that addition exactly, which is why the shelf goes to 6 dB and is shared.
 */
const val BASS_EASE_DB = -6f

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
private fun cut(db: Float) = if (db > -.01f) 0f else db

/**
 * One deck's share of the low end, as a shelf.
 *
 * Expressed in power, so that two decks given complementary shares hand the bass over with the
 * total left where it was: what one gives up, the other takes, at the same instant. Half a share
 * is 3 dB down, and a share of nothing lands on [BASS_KILL_DB] rather than silence.
 */
private fun bassShare(share: Float): Float =
    cut((10f * log10(share.coerceIn(0f, 1f).toDouble()).toFloat()).coerceAtLeast(BASS_KILL_DB))

fun soundShape(progress: Float, style: String, incoming: Boolean): SoundShape {
    val p = progress.coerceIn(0f, 1f)
    return when (style) {
        // Bass swap, handed over rather than dropped. The two curves used to be offset, which
        // left both low ends cut at once around the middle and emptied the mix of its bottom
        // exactly where it should have been strongest. They now cross at the same instant and
        // share the low end between them, so the total never stacks and never falls away.
        "club" -> smooth((p - .35f) / .30f).let { share ->
            SoundShape(bassDb = bassShare(if (incoming) share else 1f - share))
        }
        // Filter: A thins out through a rising high-pass while B opens through a low-pass.
        "sweep" -> if (incoming) SoundShape(lowPassHz = if (p >= 1f) 0f else 350f * (20_000f / 350f).pow(p))
            else SoundShape(highPassHz = if (p <= 0f) 0f else 30f * (1_500f / 30f).pow(p))
        // A hard switch leaves no overlap to clean up.
        "cut" -> SoundShape.NEUTRAL
        // Every ordinary fade: the arriving low end holds back while the leaving one steps down, so
        // the two are never both at full level. At the ends the surviving deck is heard untouched.
        else -> SoundShape(bassDb = cut(BASS_EASE_DB * (if (incoming) 1f - smooth(p) else smooth(p))))
    }
}
