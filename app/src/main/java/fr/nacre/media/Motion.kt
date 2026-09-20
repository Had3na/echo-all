package fr.nacre.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.CornerRadius
import androidx.media3.common.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Shared motion vocabulary, in the same spirit as LiquidNavigation and PlaybackPulse: every effect
// takes the "Réduire les animations" setting and becomes still rather than disappearing.

/** Sweeping highlight over a placeholder while something loads. */
@Composable
fun Modifier.shimmer(reduced: Boolean, corner: Int = 12): Modifier {
    val base = Panel
    val highlight = Lime.copy(alpha = .14f)
    val sweep = if (reduced) null else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmerSweep")
    }
    return this.background(base, RoundedCornerShape(corner.dp)).drawWithContent {
        drawContent()
        val progress = sweep?.value ?: return@drawWithContent
        val band = size.width * .55f
        val x = -band + progress * (size.width + 2 * band)
        drawRect(
            Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x, 0f), end = Offset(x + band, size.height)),
            topLeft = Offset.Zero, size = size)
    }
}

/** Lifts an item into place, each one a beat after the previous. Capped so a long list stays snappy. */
@Composable
fun Modifier.entrance(index: Int, reduced: Boolean, key: Any = Unit): Modifier {
    val progress = remember(key, index) { Animatable(if (reduced) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(key, index, reduced) {
        if (reduced) progress.snapTo(1f)
        else {
            progress.snapTo(0f)
            delay(index.coerceAtMost(8) * 45L)
            progress.animateTo(1f, tween(320))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 18.dp.toPx()
    }
}

/** Shrinks a card slightly while a finger is on it. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, reduced: Boolean): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .965f else 1f,
        if (reduced) snap() else spring(dampingRatio = .55f, stiffness = 700f), label = "pressScale")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Slow 0..1 swell, for a gradient or a glow that breathes instead of sitting flat. */
@Composable
fun rememberBreath(reduced: Boolean, periodMs: Int = 5200): Float {
    if (reduced) return .5f
    val transition = rememberInfiniteTransition(label = "breath")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "breathPhase")
    return (sin(phase) + 1f) / 2f
}

/** Halo that widens and fades around an active element. */
@Composable
fun Modifier.glowPulse(active: Boolean, reduced: Boolean, corner: Int = 28): Modifier {
    val breath = rememberBreath(reduced, 2600)
    val strength by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "glowStrength")
    val tint = Lime
    return this.drawWithContent {
        drawContent()
        if (strength <= 0.01f) return@drawWithContent
        val spread = (2.dp.toPx() + 3.dp.toPx() * breath) * strength
        drawRoundRect(tint.copy(alpha = .22f * strength * (.45f + .55f * breath)),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(corner.dp.toPx() + spread),
            style = Stroke(width = spread))
    }
}

/** Slow drifting wash of colour, for a header that should feel alive without asking for attention. */
@Composable
fun Modifier.aurora(reduced: Boolean): Modifier {
    val drift = if (reduced) null else {
        val transition = rememberInfiniteTransition(label = "aurora")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(11_000, easing = LinearEasing)), label = "auroraDrift")
    }
    val tint = Lime
    return this.drawWithContent {
        val angle = (drift?.value ?: .25f) * 2f * PI.toFloat()
        drawRect(Brush.radialGradient(
            listOf(tint.copy(alpha = .17f), Color.Transparent),
            center = Offset(size.width * (.5f + .38f * cos(angle)), size.height * (.5f + .55f * sin(angle))),
            radius = size.maxDimension * .85f))
        drawContent()
    }
}

/** The media the session is on, and whether it is actually playing. */
@Composable
fun rememberPlaying(player: Player?): Pair<String, Boolean> {
    var state by androidx.compose.runtime.remember(player) {
        androidx.compose.runtime.mutableStateOf(player?.currentMediaItem?.mediaId.orEmpty() to (player?.isPlaying == true))
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(target: Player, events: Player.Events) {
                state = target.currentMediaItem?.mediaId.orEmpty() to target.isPlaying
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return state
}

/**
 * Download control for one track: an arrow at rest, a ring that fills while yt-dlp works,
 * then a check that pops in behind an expanding ring. Tapping a running job cancels it.
 */
@Composable
fun DownloadDial(job: YouTubeDownload?, reduced: Boolean, onDownload: () -> Unit, onCancel: () -> Unit,
                 already: Boolean = false) {
    if (already && job == null) {
        IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Check, "Déjà dans ta bibliothèque. Toucher pour le reprendre.",
                Modifier.size(18.dp), tint = Muted)
        }
        return
    }
    val running = job != null && !job.finished
    val done = job?.state == DownloadState.DONE
    val failed = job?.state == DownloadState.FAILED
    val ring by animateFloatAsState(job?.progress ?: 0f, if (reduced) snap() else tween(300), label = "dialRing")
    val pop by animateFloatAsState(if (done) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = .42f, stiffness = 520f), label = "dialPop")
    // Waiting and converting have no percentage to show, so the ring turns instead of filling.
    val spin = if (reduced || !running || (job?.progress ?: 0f) > 0f) null else {
        val transition = rememberInfiniteTransition(label = "dialSpin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dialSpinAngle")
    }
    // One outward ring when the file lands, so a finished download is noticed without a banner.
    val burst = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(done, reduced) {
        if (done && !reduced) { burst.snapTo(0f); burst.animateTo(1f, tween(560)) } else burst.snapTo(if (done) 1f else 0f)
    }
    val track = Muted.copy(alpha = .28f)
    val fill = Lime
    IconButton(onClick = { if (running) onCancel() else if (!done) onDownload() }, enabled = !done) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(34.dp)) {
                val stroke = 2.4.dp.toPx()
                val inset = stroke / 2
                val box = Size(size.width - stroke, size.height - stroke)
                if (running) {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    val angle = spin?.value
                    if (angle != null) drawArc(fill, angle, 90f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    else drawArc(fill, -90f, 360f * ring, false, Offset(inset, inset), box, style = Stroke(stroke))
                } else if (done) {
                    drawArc(fill.copy(alpha = pop), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    val wave = burst.value
                    if (wave > 0f && wave < 1f) {
                        val grow = size.minDimension * .5f * (1f + wave * .9f)
                        drawCircle(fill.copy(alpha = (1f - wave) * .5f), grow, style = Stroke(stroke * (1f - wave)))
                    }
                }
            }
            when {
                done -> Icon(Icons.Rounded.Check, "Téléchargé", Modifier.size(18.dp).graphicsLayer { scaleX = pop; scaleY = pop }, tint = Lime)
                running -> Icon(Icons.Rounded.Close, "Annuler le téléchargement", Modifier.size(15.dp), tint = Muted)
                else -> Icon(Icons.Rounded.Download, "Télécharger", Modifier.size(20.dp), tint = if (failed) Muted else Lime)
            }
        }
    }
}
