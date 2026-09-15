package fr.nacre.media

import kotlin.math.pow

/** Filter targets for one deck during a transition. 0 Hz switches a filter off; effects only ever remove energy. */
data class SoundShape(val bassDb: Float = 0f, val highPassHz: Float = 0f, val lowPassHz: Float = 0f) {
    val neutral get() = bassDb == 0f && highPassHz == 0f && lowPassHz == 0f
    companion object { val NEUTRAL = SoundShape() }
}

const val BASS_KILL_DB = -30f

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
private fun cut(db: Float) = if (db > -.01f) 0f else db

fun soundShape(progress: Float, style: String, incoming: Boolean): SoundShape {
    val p = progress.coerceIn(0f, 1f)
    return when (style) {
        // Bass swap: A loses its low end first, B gets it back just after, so both basses never stack.
        "club" -> SoundShape(bassDb = cut(if (incoming) BASS_KILL_DB * (1 - smooth((p - .45f) / .25f)) else BASS_KILL_DB * smooth((p - .3f) / .25f)))
        // Filter: A thins out through a rising high-pass while B opens through a low-pass.
        "sweep" -> if (incoming) SoundShape(lowPassHz = if (p >= 1f) 0f else 350f * (20_000f / 350f).pow(p))
            else SoundShape(highPassHz = if (p <= 0f) 0f else 30f * (1_500f / 30f).pow(p))
        else -> SoundShape.NEUTRAL
    }
}
