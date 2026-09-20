package fr.nacre.media

import androidx.compose.animation.*
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.*

/** Two damped springs let the pill stretch in the direction of travel, then settle. */
@Composable
fun LiquidNavigation(selected: Int, reduced: Boolean, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val lead by animateFloatAsState(selected.toFloat(), if(reduced) snap() else spring(dampingRatio = .78f, stiffness = 430f), label = "liquidHead")
    val tail by animateFloatAsState(selected.toFloat(), if(reduced) snap() else spring(dampingRatio = .9f, stiffness = 190f), label = "liquidTail")
    val fill = MaterialTheme.colorScheme.primary
    Surface(modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = Panel, shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        Box(Modifier.background(Brush.verticalGradient(listOf(Lime.copy(alpha = .06f), Panel))).padding(6.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val cell = size.width / 6
                val left = min(lead, tail).coerceIn(0f, 5f) * cell + 3.dp.toPx()
                val right = (max(lead, tail).coerceIn(0f, 5f) + 1) * cell - 3.dp.toPx()
                drawRoundRect(fill.copy(alpha = .16f), topLeft = Offset(left, 0f), size = Size(right - left, size.height), cornerRadius = CornerRadius(26.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                val icons = listOf(Icons.Rounded.Home, Icons.Rounded.MusicNote, Icons.Rounded.PlayCircle, Icons.Rounded.Photo, Icons.Rounded.EditNote, Icons.Rounded.Storage)
                listOf("Accueil", "Musique", "Vidéos", "Photos", "Notes", "Sources").forEachIndexed { index, label ->
                    val active = index == selected
                    val tint by animateColorAsState(if(active) fill else Muted, if(reduced) snap() else tween(180), label = "tabTint")
                    val scale by animateFloatAsState(if(active) 1.08f else 1f, if(reduced) snap() else spring(), label = "tabScale")
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { if(!active) contentDescription = label }.clip(RoundedCornerShape(26.dp)).selectable(active, role = Role.Tab, onClick = { onSelect(index) }).heightIn(min = 64.dp).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icons[index], null, Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
                        AnimatedVisibility(active, enter = fadeIn(tween(if(reduced) 0 else 160)) + expandVertically(tween(if(reduced) 0 else 220)), exit = fadeOut(tween(if(reduced) 0 else 90)) + shrinkVertically(tween(if(reduced) 0 else 180))) {
                            Text(label, modifier = Modifier.padding(top = 4.dp), color = fill, maxLines = 1,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, shadow = Shadow(fill.copy(alpha = .55f), Offset.Zero, 8f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackPulse(playing: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val animate = playing && !reduced && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val phase = if(animate) {
        val transition = rememberInfiniteTransition(label = "playbackPulse")
        transition.animateFloat(0f, (2*PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulsePhase")
    } else remember { mutableFloatStateOf(0f) }
    val color = Lime
    Canvas(modifier) {
        val bar = size.width / 9f
        repeat(5) { i ->
            val height = size.height * (if(animate) .25f + .7f * abs(sin(phase.value + i*.9f)) else .35f)
            drawRoundRect(color, Offset(i*bar*2, (size.height-height)/2), Size(bar, height), CornerRadius(bar))
        }
    }
}

@Composable
fun pageEntrance(key: Any, reduced: Boolean): Modifier {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(key, reduced) {
        if(reduced) alpha.snapTo(1f) else { alpha.snapTo(0f); alpha.animateTo(1f, tween(240)) }
    }
    return Modifier.graphicsLayer { this.alpha = alpha.value; translationY = (1-alpha.value)*12.dp.toPx() }
}
