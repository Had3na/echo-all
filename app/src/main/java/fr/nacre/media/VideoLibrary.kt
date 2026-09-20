package fr.nacre.media

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class VideoProgress(val position: Long = 0, val duration: Long = 0, val watched: Long = 0) {
    val resume get() = videoResumePosition(position, duration)
    val fraction get() = if(duration > 0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f
}

@Composable
fun VideoLibrary(items: List<LibraryItem>, onPlay: (LibraryItem) -> Unit, onOrganize: (LibraryItem) -> Unit, onFavorite: (LibraryItem) -> Unit, onRemove: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("playback", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf(emptyMap<String, VideoProgress>()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(items, revision) {
        delay(60)
        progress = withContext(Dispatchers.IO) { items.associate { it.uri to VideoProgress(prefs.getLong(it.uri, 0), prefs.getLong("duration:" + it.uri, it.durationMs), prefs.getLong("watched:" + it.uri, 0)) } }
    }
    val resume = items.filter { (progress[it.uri]?.resume ?: 0) > 0 }.sortedByDescending { progress[it.uri]?.watched }.take(12)
    LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 190.dp)) {
        if(resume.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Continuer à regarder", style = MaterialTheme.typography.titleLarge) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume, key = { it.uri }) { item ->
                        VideoTile(item, progress[item.uri] ?: VideoProgress(), Modifier.width(260.dp), true, { onPlay(item) }, { onOrganize(item) }, { onFavorite(item) }, { onRemove(item) })
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Toutes les vidéos", style = MaterialTheme.typography.titleLarge) }
        }
        items(items, key = { it.uri }) { item ->
            VideoTile(item, progress[item.uri] ?: VideoProgress(duration = item.durationMs), Modifier.fillMaxWidth(), false, { onPlay(item) }, { onOrganize(item) }, { onFavorite(item) }, { onRemove(item) })
        }
    }
}

@Composable
private fun VideoTile(item: LibraryItem, progress: VideoProgress, modifier: Modifier, resume: Boolean, onPlay: () -> Unit, onOrganize: () -> Unit, onFavorite: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val chooseCover = rememberCoverPicker(item.uri)
    val context = LocalContext.current
    Surface(onClick = onPlay, modifier = modifier, color = Panel, shape = RoundedCornerShape(22.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Lime.copy(alpha = .2f), Ink))), contentAlignment = Alignment.Center) {
                MediaThumbnail(item, Modifier.fillMaxSize())
                Text(if(progress.duration > 0) mediaClock(progress.duration) else "Vidéo", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                if(progress.position > 0 && progress.duration > 0) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if(resume) "Reprendre à " + mediaClock(progress.resume) else item.videoGroup(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options pour " + item.title) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Classer la vidéo") }, onClick = { menu = false; onOrganize() })
                        DropdownMenuItem(text = { Text("Choisir une vignette") }, onClick = { menu = false; chooseCover() })
                        DropdownMenuItem(text = { Text(if(item.favorite) "Retirer des favoris" else "Ajouter aux favoris") }, onClick = { menu = false; onFavorite() })
                        DropdownMenuItem(text = { Text("Partager") }, onClick = { menu = false; shareMedia(context, item) })
                        DropdownMenuItem(text = { Text("Retirer de la bibliothèque") }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}
