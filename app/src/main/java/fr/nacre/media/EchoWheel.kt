package fr.nacre.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

fun wheelTarget(x: Float, y: Float, radius: Float): Int? {
    if (y > -radius * .2f || hypot(x, y) < radius * .5f || hypot(x, y) > radius * 1.65f) return null
    return (0..4).minByOrNull { i -> val angle = Math.toRadians(200.0 + i * 35.0); hypot(x - cos(angle).toFloat() * radius, y - sin(angle).toFloat() * radius) }
}

@Composable
fun EchoWheel(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 112.dp.toPx() }
    val choose by rememberUpdatedState(onSelect)
    val labels = listOf("Accueil", "Musique", "Vidéos", "Photos", "Sources")
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.MusicNote, Icons.Rounded.PlayCircle, Icons.Rounded.Photo, Icons.Rounded.Storage)
    BackHandler(open) { open = false; hovered = null }
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
        if (open) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .72f)).clickable { open = false; hovered = null })
            Text(hovered?.let { labels[it] } ?: "Glisse vers une rubrique, puis relâche", color = Lime, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 214.dp))
            labels.forEachIndexed { index, label ->
                val angle = Math.toRadians(200.0 + index * 35.0)
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(52.dp).offset(x = (cos(angle) * 112).dp, y = (sin(angle) * 112).dp)) {
                    FilledTonalIconButton(onClick = { choose(index); open = false }, modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (hovered == index || selected == index) Lime else Panel, contentColor = if (hovered == index || selected == index) Ink else Lime)) { Icon(icons[index], label) }
                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.TopCenter).offset(y = 54.dp))
                }
            }
        }
        Box(Modifier.padding(bottom = 8.dp).size(52.dp).clip(CircleShape).background(Lime)
            .pointerInput(radius) {
                detectDragGesturesAfterLongPress(onDragStart = { drag = Offset.Zero; open = true; hovered = null },
                    onDragCancel = { open = false; hovered = null },
                    onDragEnd = { hovered?.let { choose(it) }; open = false; hovered = null },
                    onDrag = { change, amount -> change.consume(); drag += amount; hovered = wheelTarget(drag.x, drag.y, radius) })
            }.clickable { open = !open }, contentAlignment = Alignment.Center) {
            Icon(if (open) Icons.Rounded.Close else Icons.Rounded.BlurOn, "Navigation : maintenir et glisser, ou toucher pour ouvrir", tint = Ink)
        }
    }
}
