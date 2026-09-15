package fr.nacre.media

data class MixGains(val outgoing: Float, val incoming: Float)

// Constant-sum gain avoids doubling peak amplitude when two similar tracks overlap.
fun mixGains(elapsedMs: Long, durationMs: Long): MixGains {
    val progress = if (durationMs <= 0) 1f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    return MixGains(1f - progress, progress)
}
