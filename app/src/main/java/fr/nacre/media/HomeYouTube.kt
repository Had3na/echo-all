@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.nacre.media

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private const val HOME_RESULTS = 5

/** What the little filter beside the search field offers. */
private enum class HomeKind(val label: String, val hint: String) {
    MUSIC("Musique", "Un titre, un artiste…"),
    VIDEO("Vidéos", "Une vidéo…"),
    PLAYLIST("Playlists", "Une playlist…"),
    CHANNEL("Chaînes", "Une chaîne…");

    val filter: String get() = when (this) {
        MUSIC -> YouTube.FILTER_MUSIC
        VIDEO -> YouTube.FILTER_VIDEOS
        PLAYLIST -> YouTube.FILTER_PLAYLISTS
        CHANNEL -> YouTube.FILTER_CHANNELS
    }
}

/**
 * YouTube straight from the home screen: type, listen, download. Anything beyond that — playlists,
 * channels, videos, quality settings — lives in the full space under Sources.
 */
@Composable
fun HomeYouTubeSearch(vm: LibraryViewModel, player: Player?, prefs: SharedPreferences,
                      reduced: Boolean, onOpenVideo: () -> Unit, onOpenFull: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YouTubeResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val downloads by YouTubeDownloads.active.collectAsState()
    LaunchedEffect(downloads) { if (downloads.any { it.done }) vm.collectYouTube() }

    val breath = rememberBreath(reduced)
    val (playingId, isPlaying) = rememberPlaying(player)
    val owned = rememberDownloadedVideos()
    val library by vm.items.collectAsState()
    var recent by remember { mutableStateOf(recentSearches(prefs)) }
    var kind by rememberSaveable { mutableStateOf(HomeKind.MUSIC) }
    var kindMenu by remember { mutableStateOf(false) }
    val open = results.isNotEmpty() || loading || message.isNotBlank()
    val pad by animateDpAsState(if (open) 18.dp else 14.dp, if (reduced) snap() else spring(), label = "ytPad")

    fun search() {
        val text = query.trim()
        if (text.isBlank() || loading) return
        keyboard?.hide()
        loading = true; message = ""
        scope.launch {
            try {
                // A pasted link has no business being searched: hand it to the full space.
                if (isYouTubeLink(text)) { message = "Lien YouTube : ouvre l’espace complet pour le lire."; return@launch }
                results = YouTube.search(text, kind.filter).take(HOME_RESULTS)
                noteSearch(prefs, text); recent = recentSearches(prefs)
                if (results.isEmpty()) message = "Aucun résultat."
            } catch (_: Exception) {
                message = "Recherche impossible. Vérifie ta connexion."
            } finally { loading = false }
        }
    }

    fun play(item: YouTubeResult) {
        if (streamingBlocked(context)) { message = "Streaming limité au Wi-Fi. Change-le dans les réglages."; return }
        val controller = player ?: run { message = "Le lecteur démarre… Réessaie dans un instant."; return }
        val queue = results.filter { it.kind == YouTubeKind.VIDEO && !it.live }
        if (queue.isEmpty()) return
        YouTube.forget(item.url)
        controller.setMediaItems(queue.map { entry ->
            val watch = cleanWatchUrl(entry.url)
            val (artist, title) = youtubeArtistTitle(entry.title, entry.uploader)
            MediaItem.Builder().setMediaId(watch).setUri(youtubePlaybackUri(watch, kind == HomeKind.VIDEO))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist.ifBlank { "YouTube" })
                    .setAlbumTitle("YouTube")
                    .setArtworkUri(entry.thumbnail.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setExtras(Bundle().apply { putBoolean("video", kind == HomeKind.VIDEO); putBoolean("tagged", true) }).build())
                .build()
        }, queue.indexOfFirst { it.url == item.url }.coerceAtLeast(0), 0)
        controller.prepare(); controller.play()
        if (kind == HomeKind.VIDEO) onOpenVideo()
    }

    /** A playlist or a channel has a page of its own; the full space knows how to open it. */
    fun open(item: YouTubeResult) {
        if (item.kind != YouTubeKind.VIDEO) onOpenFull(item.url) else play(item)
    }

    Surface(shape = RoundedCornerShape(28.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp))
                    .background(Lime.copy(alpha = .08f + .07f * breath)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SmartDisplay, null, Modifier.size(20.dp), tint = Lime)
                }
                Text("YouTube", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenFull(null) }) {
                    Text("Tout voir"); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
                }
            }

            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(kind.hint) },
                trailingIcon = {
                    Box {
                        IconButton(onClick = { kindMenu = true }) { Icon(Icons.Rounded.Tune, "Type de recherche", tint = Lime) }
                        DropdownMenu(kindMenu, { kindMenu = false }) {
                            HomeKind.entries.forEach { entry ->
                                DropdownMenuItem(text = { Text(entry.label) }, onClick = {
                                    kind = entry; kindMenu = false; results = emptyList(); message = ""
                                    if (query.isNotBlank()) search()
                                }, leadingIcon = {
                                    if (kind == entry) Icon(Icons.Rounded.Check, null, tint = Lime)
                                })
                            }
                        }
                    }
                },
                leadingIcon = {
                    val hunt by animateFloatAsState(if (loading && !reduced) .85f + .3f * breath else 1f,
                        label = "searchPulse")
                    Icon(Icons.Rounded.Search, null,
                        Modifier.graphicsLayer { scaleX = hunt; scaleY = hunt },
                        tint = if (loading) Lime else LocalContentColor.current)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }))

            AnimatedVisibility(recent.isNotEmpty() && results.isEmpty() && !loading,
                enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { term -> AssistChip({ query = term; search() }, { Text(term, fontSize = 12.sp) }) }
                }
            }

            AnimatedVisibility(loading,
                enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { Box(Modifier.fillMaxWidth().height(56.dp).shimmer(reduced, 14)) }
                }
            }

            AnimatedVisibility(message.isNotBlank() && !loading,
                enter = fadeIn(), exit = fadeOut()) { Text(message, color = Lime, fontSize = 12.sp) }

            AnimatedVisibility(results.isNotEmpty() && !loading,
                enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.forEachIndexed { index, result ->
                        val id = (youtubeVideoId(result.url) ?: result.url) + "|a"
                        HomeResultRow(result, downloads.firstOrNull { it.id == id }, index, reduced,
                            playing = playingId == cleanWatchUrl(result.url) && isPlaying,
                            already = result.videoId in owned,
                            kept = library.any { entry -> entry.uri == cleanWatchUrl(result.url) },
                            onKeep = { keepFromResult(vm, result) },
                            onPlay = { open(result) },
                            onDownload = {
                                YouTubeDownloads.enqueue(context, result, false,
                                    youtubeAudioQuality(prefs), youtubeVideoQuality(prefs), youtubeDownloadFormat(prefs))
                            },
                            onCancel = { YouTubeDownloads.cancel(context, id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeResultRow(result: YouTubeResult, job: YouTubeDownload?, index: Int, reduced: Boolean,
                          playing: Boolean, already: Boolean, kept: Boolean, onKeep: () -> Unit,
                          onPlay: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val (artist, title) = remember(result.url) { youtubeArtistTitle(result.title, result.uploader) }
    val placeholder = rememberVectorPainter(Icons.Rounded.SmartDisplay)
    Row(Modifier.fillMaxWidth()
        .entrance(index, reduced, result.url)
        .pressScale(interaction, reduced)
        .clip(RoundedCornerShape(14.dp))
        .background(Ink)
        .clickable(interaction, null, onClick = onPlay)
        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(result.thumbnail.ifBlank { null }, null,
            Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(9.dp)).background(Panel),
            contentScale = ContentScale.Crop, placeholder = placeholder, error = placeholder, fallback = placeholder)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (playing) PlaybackPulse(true, reduced, Modifier.size(width = 14.dp, height = 12.dp))
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(listOf(artist, if (result.live) "En direct" else mediaClock(result.durationMs))
                .filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!result.live && result.kind == YouTubeKind.VIDEO) {
            IconButton(onClick = onKeep, enabled = !kept) {
                Icon(if (kept) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                    if (kept) "Déjà gardé" else "Garder sans télécharger",
                    Modifier.size(20.dp), tint = if (kept) Muted else Lime)
            }
            DownloadDial(job, reduced, onDownload, onCancel, already)
        }
    }
}
