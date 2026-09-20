@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.nacre.media

import android.Manifest
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class YouTubeTab(val label: String, val icon: ImageVector) {
    MUSIC("Musique", Icons.Rounded.MusicNote),
    VIDEOS("Vidéos", Icons.Rounded.Movie),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
    CHANNELS("Chaînes", Icons.Rounded.Person)
}

private fun filterFor(tab: YouTubeTab): String = when (tab) {
    YouTubeTab.MUSIC -> YouTube.FILTER_MUSIC
    YouTubeTab.VIDEOS -> YouTube.FILTER_VIDEOS
    YouTubeTab.PLAYLISTS -> YouTube.FILTER_PLAYLISTS
    YouTubeTab.CHANNELS -> YouTube.FILTER_CHANNELS
}

internal fun recentSearches(prefs: SharedPreferences): List<String> =
    prefs.getString("ytRecent", "").orEmpty().split('\n').filter { it.isNotBlank() }

internal fun noteSearch(prefs: SharedPreferences, term: String) {
    prefs.edit().putString("ytRecent", pushRecent(recentSearches(prefs), term).joinToString("\n")).apply()
}

/** Video ids whose file is already in the library, so the search stops offering them again. */
@Composable
internal fun rememberDownloadedVideos(): Set<String> {
    val context = LocalContext.current
    var ids by remember { mutableStateOf(emptySet<String>()) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val stop = StudioEvents.listen { key -> if (key == "youtube") revision++ }
        onDispose(stop)
    }
    LaunchedEffect(revision) {
        ids = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { StudioStore(context).cacheAll("yt:").keys }.getOrDefault(emptySet())
        }
    }
    return ids
}

internal fun youtubeAudioQuality(prefs: SharedPreferences) =
    runCatching { AudioQuality.valueOf(prefs.getString("ytAudio", null) ?: "") }.getOrDefault(AudioQuality.BEST)

internal fun youtubeVideoQuality(prefs: SharedPreferences) =
    runCatching { VideoQuality.valueOf(prefs.getString("ytVideo", null) ?: "") }.getOrDefault(VideoQuality.FHD)

internal fun youtubeDownloadFormat(prefs: SharedPreferences) =
    runCatching { DownloadFormat.valueOf(prefs.getString("ytFormat", null) ?: "") }.getOrDefault(DownloadFormat.ORIGINAL)

@Composable
private fun YtThumb(url: String, width: Int, height: Int, round: Int, fallback: ImageVector) {
    val placeholder = rememberVectorPainter(fallback)
    AsyncImage(url.ifBlank { null }, null, Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(round.dp)).background(Panel),
        contentScale = ContentScale.Crop, placeholder = placeholder, error = placeholder, fallback = placeholder)
}

private fun YouTubeResult.asMediaItem(video: Boolean): MediaItem {
    val watch = cleanWatchUrl(url)
    val (artist, clean) = youtubeArtistTitle(title, uploader)
    return MediaItem.Builder().setMediaId(watch).setUri(youtubePlaybackUri(watch, video))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(clean).setArtist(artist.ifBlank { "YouTube" })
            .setAlbumTitle("YouTube")
            .setArtworkUri(thumbnail.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(Bundle().apply { putBoolean("video", video); putBoolean("tagged", true) }).build())
        .build()
}

/**
 * Search, listen to and download from YouTube. Playback resolves each track through NewPipeExtractor
 * as the player opens it; downloads go through yt-dlp, which reaches higher video qualities.
 */
@Composable
fun YouTubeScreen(vm: LibraryViewModel, player: Player?, prefs: SharedPreferences, initialLink: String?,
                  onLinkConsumed: () -> Unit, onOpenVideo: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var tab by remember { mutableStateOf(YouTubeTab.MUSIC) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YouTubeResult>?>(null) }
    var page by remember { mutableStateOf<YouTubePage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<List<YouTubeResult>, Boolean>?>(null) }

    var audioQuality by remember { mutableStateOf(youtubeAudioQuality(prefs)) }
    var videoQuality by remember { mutableStateOf(youtubeVideoQuality(prefs)) }
    var format by remember { mutableStateOf(youtubeDownloadFormat(prefs)) }

    val downloads by YouTubeDownloads.active.collectAsState()
    val reduced = remember(prefs) { prefs.getBoolean("reduceMotion", false) }
    val owned = rememberDownloadedVideos()
    val library by vm.items.collectAsState()
    var recent by remember { mutableStateOf(recentSearches(prefs)) }

    // A finished download becomes a normal library entry, exactly like an Internet Archive one.
    LaunchedEffect(downloads) { if (downloads.any { it.done }) vm.collectYouTube() }

    fun search(text: String = query) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || loading) return
        loading = true; message = ""; page = null
        scope.launch {
            try {
                results = YouTube.search(trimmed, filterFor(tab))
                noteSearch(prefs, trimmed); recent = recentSearches(prefs)
                if (results.isNullOrEmpty()) message = "Aucun résultat."
            } catch (error: Exception) {
                message = "Recherche impossible. Vérifie ta connexion, ou mets yt-dlp à jour depuis les réglages."
            } finally { loading = false }
        }
    }

    fun openPage(result: YouTubeResult) {
        loading = true; message = ""
        scope.launch {
            try {
                page = if (result.kind == YouTubeKind.CHANNEL) YouTube.channel(result.url) else YouTube.playlist(result.url)
                if (page?.items.isNullOrEmpty()) message = "Rien à lire ici."
            } catch (error: Exception) {
                message = "Impossible d’ouvrir cet élément."
            } finally { loading = false }
        }
    }

    fun play(item: YouTubeResult, queue: List<YouTubeResult>, video: Boolean) {
        if (streamingBlocked(context)) { message = "Streaming limité au Wi-Fi. Change-le dans les réglages."; return }
        val controller = player ?: run { message = "Le lecteur démarre… Réessaie dans un instant."; return }
        val playable = queue.filter { it.kind == YouTubeKind.VIDEO && !it.live }
        if (playable.isEmpty()) { message = "Rien de lisible dans cette sélection."; return }
        val index = playable.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
        // The URL YouTube gave earlier may have expired: drop it so the player asks for a fresh one.
        YouTube.forget(item.url)
        controller.setMediaItems(playable.map { it.asMediaItem(video) }, index, 0)
        controller.prepare(); controller.play()
        message = "Lecture : " + youtubeArtistTitle(item.title, item.uploader).second
        if (video) onOpenVideo()
    }

    fun download(items: List<YouTubeResult>, video: Boolean) {
        val videos = items.filter { it.kind == YouTubeKind.VIDEO && !it.live }
        if (videos.isEmpty()) { message = "Rien à télécharger ici."; return }
        videos.forEach { YouTubeDownloads.enqueue(context, it, video, audioQuality, videoQuality, format) }
        message = if (videos.size == 1) "Téléchargement lancé." else "${videos.size} téléchargements en file."
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = pending
        pending = null
        if (granted && waiting != null) download(waiting.first, waiting.second)
        else if (!granted) message = "Sans l’autorisation de stockage, impossible d’enregistrer sur cette version d’Android."
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun requestDownload(items: List<YouTubeResult>, video: Boolean) {
        if (youtubeDownloadsNeedStorage && !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pending = items to video
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else download(items, video)
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !context.hasPermission("android.permission.POST_NOTIFICATIONS"))
            notifications.launch("android.permission.POST_NOTIFICATIONS")
    }

    /** A pasted or shared link opens the video, playlist or channel it points at. */
    fun openLink(text: String) {
        val channel = youtubeChannelUrl(text)
        val list = youtubePlaylistId(text)
        val video = youtubeVideoId(text)
        when {
            channel != null -> openPage(YouTubeResult(channel, "Chaîne", "", YouTubeKind.CHANNEL))
            list != null -> openPage(YouTubeResult(youtubePlaylistUrl(list), "Playlist", "", YouTubeKind.PLAYLIST))
            video != null -> {
                loading = true; message = ""
                scope.launch {
                    try {
                        val media = YouTube.resolve(youtubeWatchUrl(video), false, audioQuality, videoQuality)
                        val single = YouTubeResult(media.watchUrl, media.title, media.uploader, YouTubeKind.VIDEO,
                            media.durationMs, media.thumbnail.ifBlank { youtubeThumbnail(video) })
                        results = listOf(single); page = null
                    } catch (error: Exception) { message = "Lien illisible : " + (error.message ?: "réessaie") }
                    finally { loading = false }
                }
            }
            else -> message = "Ce texte ne contient pas de lien YouTube."
        }
    }

    // A link shared from the YouTube app lands straight on its video, playlist or channel.
    LaunchedEffect(initialLink) {
        if (!initialLink.isNullOrBlank()) { openLink(initialLink); onLinkConsumed() }
    }

    val current = page
    Dialog(onDismissRequest = { if (current != null) page = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current != null) IconButton(onClick = { page = null; message = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour aux résultats")
                        }
                        Text("YouTube", fontSize = 22.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Tune, "Qualité et format") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                    }
                }

                if (downloads.isNotEmpty()) {
                    item { Text("Téléchargements", color = Lime) }
                    items(downloads, key = { "yt" + it.id }) { job -> DownloadRow(job) { YouTubeDownloads.cancel(context, job.id) } }
                }

                if (current == null) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            YouTubeTab.entries.forEach { entry ->
                                FilterChip(tab == entry, { tab = entry; results = null; if (query.isNotBlank()) search() },
                                    { Text(entry.label) }, leadingIcon = { Icon(entry.icon, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                    item {
                        OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            placeholder = { Text(if (tab == YouTubeTab.CHANNELS) "Nom d’une chaîne…" else "Artiste, titre, album…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    val text = clipboard.getText()?.text.orEmpty()
                                    if (text.isBlank()) message = "Le presse-papier est vide." else { query = ""; openLink(text) }
                                }) { Icon(Icons.Rounded.ContentPaste, "Coller un lien YouTube") }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (isYouTubeLink(query)) openLink(query) else search()
                            }))
                    }
                    if (recent.isNotEmpty() && results == null) item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            recent.forEach { term ->
                                AssistChip({ query = term; search(term) }, { Text(term) })
                            }
                        }
                    }
                    item {
                        Text("Lecture et téléchargement passent par l’extraction directe : c’est hors des conditions d’utilisation de YouTube, et ça casse parfois quand YouTube change son site. Le bouton de mise à jour, dans les réglages ⚙, répare la plupart des pannes.",
                            color = Muted, fontSize = 11.sp)
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Text(message, color = Lime) }

                    itemsIndexed(results.orEmpty(), key = { _, entry -> entry.url }) { index, result ->
                        val video = tab == YouTubeTab.VIDEOS
                        val id = (youtubeVideoId(result.url) ?: result.url) + if (video) "|v" else "|a"
                        ResultRow(result, index, reduced, downloads.firstOrNull { it.id == id },
                            already = !video && result.videoId in owned,
                            kept = library.any { entry -> entry.uri == cleanWatchUrl(result.url) },
                            onKeep = { keepFromResult(vm, result) },
                            onOpen = {
                                if (result.kind == YouTubeKind.VIDEO) play(result, results.orEmpty(), video)
                                else openPage(result)
                            },
                            onDownload = { requestDownload(listOf(result), video) },
                            onCancel = { YouTubeDownloads.cancel(context, id) })
                    }
                } else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            YtThumb(current.thumbnail, 96, 96, if (current.kind == YouTubeKind.CHANNEL) 48 else 14,
                                if (current.kind == YouTubeKind.CHANNEL) Icons.Rounded.Person else Icons.AutoMirrored.Rounded.QueueMusic)
                            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(current.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (current.subtitle.isNotBlank()) Text(current.subtitle, color = Muted, fontSize = 12.sp)
                                Text("${current.items.size} titres chargés", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Text(message, color = Lime) }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { current.items.firstOrNull()?.let { play(it, current.items, false) } }) {
                                Icon(Icons.Rounded.PlayArrow, null); Text(" Tout écouter")
                            }
                            OutlinedButton(onClick = {
                                val shuffled = current.items.shuffled()
                                shuffled.firstOrNull()?.let { play(it, shuffled, false) }
                            }) { Icon(Icons.Rounded.Shuffle, null); Text(" Aléatoire") }
                            FilledTonalButton(onClick = { requestDownload(current.items, false) }) {
                                Icon(Icons.Rounded.Download, null); Text(" Tout télécharger")
                            }
                        }
                    }
                    itemsIndexed(current.items, key = { _, entry -> entry.url }) { index, result ->
                        val id = (youtubeVideoId(result.url) ?: result.url) + "|a"
                        ResultRow(result, index, reduced, downloads.firstOrNull { it.id == id },
                            already = result.videoId in owned,
                            kept = library.any { entry -> entry.uri == cleanWatchUrl(result.url) },
                            onKeep = { keepFromResult(vm, result) },
                            onOpen = { play(result, current.items, false) },
                            onDownload = { requestDownload(listOf(result), false) },
                            onCancel = { YouTubeDownloads.cancel(context, id) })
                    }
                }
            }
        }
    }

    if (settingsOpen) YouTubeSettings(audioQuality, videoQuality, format,
        onAudio = { audioQuality = it; prefs.edit().putString("ytAudio", it.name).apply() },
        onVideo = { videoQuality = it; prefs.edit().putString("ytVideo", it.name).apply() },
        onFormat = { format = it; prefs.edit().putString("ytFormat", it.name).apply() },
        onUpdate = { scope.launch { message = YouTubeDownloads.updateEngine(context); settingsOpen = false } }) { settingsOpen = false }
}

@Composable
private fun ResultRow(result: YouTubeResult, index: Int, reduced: Boolean, job: YouTubeDownload?,
                      already: Boolean, kept: Boolean, onKeep: () -> Unit,
                      onOpen: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val detail = when (result.kind) {
        YouTubeKind.VIDEO -> listOf(result.uploader, if (result.live) "En direct" else mediaClock(result.durationMs), formatViews(result.viewCount))
        YouTubeKind.PLAYLIST -> listOf(result.uploader, if (result.itemCount > 0) "${result.itemCount} titres" else "")
        YouTubeKind.CHANNEL -> listOf(formatSubscribers(result.itemCount))
    }.filter { it.isNotBlank() }.joinToString(" · ")
    val icon = when (result.kind) {
        YouTubeKind.VIDEO -> Icons.Rounded.MusicNote
        YouTubeKind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
        YouTubeKind.CHANNEL -> Icons.Rounded.Person
    }
    Row(Modifier.fillMaxWidth()
        .entrance(index, reduced, result.url)
        .pressScale(interaction, reduced)
        .clip(RoundedCornerShape(14.dp)).background(Panel)
        .clickable(interaction, null, onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (result.kind == YouTubeKind.CHANNEL) YtThumb(result.thumbnail, 56, 56, 28, icon)
        else YtThumb(result.thumbnail, 84, 56, 10, icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(cleanYouTubeTitle(result.title), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (result.kind == YouTubeKind.VIDEO && !result.live) {
            IconButton(onClick = onKeep, enabled = !kept) {
                Icon(if (kept) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd,
                    if (kept) "Déjà gardé" else "Garder sans télécharger", tint = if (kept) Muted else Lime)
            }
            DownloadDial(job, reduced, onDownload, onCancel, already)
        }
    }
}

/** Shared by both YouTube screens: the entry keeps the watch address, like a radio keeps its stream. */
internal fun keepFromResult(vm: LibraryViewModel, result: YouTubeResult) {
    val (artist, title) = youtubeArtistTitle(result.title, result.uploader)
    vm.keepYouTube(result.url, title, artist, result.durationMs,
        result.thumbnail.ifBlank { youtubeThumbnail(result.videoId) })
}

@Composable
private fun DownloadRow(job: YouTubeDownload, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            when (job.state) {
                DownloadState.QUEUED -> Text("En attente…", color = Muted, fontSize = 11.sp)
                DownloadState.PREPARING -> Text("Préparation du moteur… (la première fois seulement)", color = Muted, fontSize = 11.sp)
                DownloadState.UPDATING -> Text("Mise à jour de yt-dlp… (une fois par semaine, en Wi-Fi)", color = Muted, fontSize = 11.sp)
                DownloadState.RUNNING ->
                    if (job.progress > 0f) LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                DownloadState.CONVERTING -> Text("Conversion et pochette…", color = Muted, fontSize = 11.sp)
                DownloadState.DONE -> Text("Terminé · ajout à la bibliothèque…", color = Muted, fontSize = 11.sp)
                DownloadState.CANCELLED -> Text("Annulé", color = Muted, fontSize = 11.sp)
                DownloadState.FAILED -> Text(job.error.ifBlank { "Échec" }, color = Muted, fontSize = 11.sp)
            }
        }
        if (!job.finished) IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Annuler ce téléchargement") }
    }
}

@Composable
private fun YouTubeSettings(audio: AudioQuality, video: VideoQuality, format: DownloadFormat,
                            onAudio: (AudioQuality) -> Unit, onVideo: (VideoQuality) -> Unit,
                            onFormat: (DownloadFormat) -> Unit, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Qualité et format", fontSize = 22.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                    }
                }
                item { Text("Audio", style = MaterialTheme.typography.titleMedium) }
                items(AudioQuality.entries, key = { "a" + it.name }) { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .clickable { onAudio(entry) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(audio == entry, { onAudio(entry) })
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(entry.label, fontWeight = FontWeight.Medium)
                            Text(entry.details, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                item { Text("Format des téléchargements audio", style = MaterialTheme.typography.titleMedium) }
                items(DownloadFormat.entries, key = { "f" + it.name }) { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .clickable { onFormat(entry) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(format == entry, { onFormat(entry) })
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(entry.label, fontWeight = FontWeight.Medium)
                            Text(entry.details, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                item { Text("Vidéo téléchargée", style = MaterialTheme.typography.titleMedium) }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoQuality.entries.forEach { entry ->
                            FilterChip(video == entry, { onVideo(entry) }, { Text(entry.label) })
                        }
                    }
                }
                item {
                    Text("La lecture vidéo dans l’application est limitée à 720p : au-delà, YouTube sépare l’image et le son. Les téléchargements n’ont pas cette limite, yt-dlp recolle les deux.",
                        color = Muted, fontSize = 11.sp)
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Moteur yt-dlp", style = MaterialTheme.typography.titleMedium)
                        Text("Quand YouTube change son site, les téléchargements s’arrêtent de marcher. Cette mise à jour récupère la dernière version de yt-dlp sans réinstaller Echo-All.",
                            color = Muted, fontSize = 11.sp)
                        Button(onClick = onUpdate) { Icon(Icons.Rounded.Refresh, null); Text(" Mettre yt-dlp à jour") }
                    }
                }
            }
        }
    }
}
