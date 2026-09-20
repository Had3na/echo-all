package fr.nacre.media

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun MusicCatalog(library: List<LibraryItem>, artists: Boolean, onPlay: (LibraryItem, List<LibraryItem>) -> Unit, onFavorite: (LibraryItem) -> Unit) {
    val groups = remember(library, artists) { musicGroups(library, artists) }
    var selected by rememberSaveable(artists) { mutableStateOf<String?>(null) }
    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 190.dp)) {
        items(groups, key = { it.key }) { group ->
            Surface(onClick = { selected = group.key }, color = Panel, shape = RoundedCornerShape(if(artists) 28.dp else 20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(if(artists) 64.dp else 14.dp)).background(Ink), contentAlignment = Alignment.Center) {
                        MediaThumbnail(group.tracks.first(), Modifier.fillMaxSize())
                    }
                    Text(group.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(group.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
    groups.find { it.key == selected }?.let { group -> MusicGroupPage(group, artists, onPlay, onFavorite) { selected = null } }
}

@Composable
private fun MusicGroupPage(group: MusicGroup, artistPage: Boolean, onPlay: (LibraryItem, List<LibraryItem>) -> Unit, onFavorite: (LibraryItem) -> Unit, onDismiss: () -> Unit) {
    var albumKey by rememberSaveable(group.key) { mutableStateOf<String?>(null) }
    val albums = remember(group) { musicGroups(group.tracks, false) }
    val album = albums.find { it.key == albumKey }
    val tracks = album?.tracks ?: group.tracks
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if(artistPage) "ARTISTE" else "ALBUM", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(108.dp).clip(RoundedCornerShape(24.dp)).background(Panel), contentAlignment = Alignment.Center) { MediaThumbnail(tracks.first(), Modifier.fillMaxSize()) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.title, style = MaterialTheme.typography.headlineLarge)
                            Text(group.subtitle, color = Muted)
                            Text(mediaClock(tracks.sumOf { it.durationMs }), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                if(artistPage) item {
                    Text("Albums", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(album == null, { albumKey = null }, { Text("Tous les titres") })
                        albums.forEach { entry -> FilterChip(albumKey == entry.key, { albumKey = entry.key }, { Text(entry.title) }) }
                    }
                }
                item {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    // Measuring the tempos now means the next shuffle of this album already has them.
                    LaunchedEffect(tracks) { TempoScanner.requestAll(context, tracks.map { it.uri }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onPlay(tracks.first(), tracks) }) { Icon(Icons.Rounded.PlayArrow, null); Text("Tout écouter") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val order = shuffleForPlayback(context, tracks)
                                order.firstOrNull()?.let { onPlay(it, order) }
                            }
                        }) { Icon(Icons.Rounded.Shuffle, null); Text("Aléatoire") }
                    }
                }
                items(tracks, key = { it.uri }) { track ->
                    Surface(onClick = { onPlay(track, tracks) }, color = Panel, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(listOf(track.album, mediaClock(track.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { onFavorite(track) }) { Icon(if(track.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if(track.favorite) "Retirer des favoris" else "Ajouter aux favoris", tint = Lime) }
                        }
                    }
                }
            }
        }
    }
}
