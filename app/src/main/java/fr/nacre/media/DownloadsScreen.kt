package fr.nacre.media

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun megabytes(bytes: Long) = when {
    bytes <= 0 -> "0 Mo"
    bytes >= 1_073_741_824 -> String.format(Locale.FRANCE, "%.2f Go", bytes / 1_073_741_824.0)
    else -> String.format(Locale.FRANCE, "%.0f Mo", bytes / 1_048_576.0)
}

/**
 * Everything being downloaded, and everything already downloaded, in one place: YouTube through
 * yt-dlp and the Internet Archive through Android's own manager. A failure can be run again from
 * here rather than by finding the track a second time.
 */
@Composable
fun DownloadsScreen(vm: LibraryViewModel, prefs: android.content.SharedPreferences, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = remember(prefs) { prefs.getBoolean("reduceMotion", false) }

    var torrentsOpen by remember { mutableStateOf(false) }
    val torrents by TorrentStore.active.collectAsState()
    val youtube by YouTubeDownloads.active.collectAsState()
    var archive by remember { mutableStateOf(emptyList<DownloadProgress>()) }
    var usage by remember { mutableStateOf<DownloadUsage?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { TorrentStore.initialize(context) } }
        while (true) {
            archive = withContext(Dispatchers.IO) { runCatching { FreeDownloads.progress(context) }.getOrDefault(emptyList()) }
            if (archive.any { it.status == DownloadManager.STATUS_SUCCESSFUL || it.status == DownloadManager.STATUS_FAILED }) vm.collectDownloads()
            if (youtube.any { it.done }) vm.collectYouTube()
            delay(1_000)
        }
    }
    // Recount after every change, so the figure never lags behind what was just saved or removed.
    LaunchedEffect(youtube.size, archive.size) {
        usage = withContext(Dispatchers.IO) { runCatching { downloadUsage(context) }.getOrNull() }
    }



    val active = youtube.filter { !it.finished }
    val failed = youtube.filter { it.state == DownloadState.FAILED }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Téléchargements", fontSize = 22.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                    }
                }

                item {
                    Surface(shape = RoundedCornerShape(20.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sur ton téléphone", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp)
                            val current = usage
                            if (current == null) Box(Modifier.fillMaxWidth().height(26.dp).shimmer(reduced, 8))
                            else Text("${current.files} fichiers · ${megabytes(current.bytes)}",
                                style = MaterialTheme.typography.titleLarge)
                            Text("Dans Musique/Echo-All et Films/Echo-All. Pour en supprimer, passe par le menu ⋮ d’un média : Echo-All ne touche pas à tes fichiers tout seul.",
                                color = Muted, fontSize = 11.sp)
                        }
                    }
                }

                item { Button(onClick = { torrentsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(if (torrents.isEmpty()) "Télécharger un torrent" else "Torrents · ${torrents.count { it.active }} en cours") } }

                if (active.isEmpty() && failed.isEmpty() && archive.isEmpty() && torrents.none { it.active }) item {
                    Text("Aucun téléchargement en cours.", color = Muted, modifier = Modifier.padding(top = 8.dp))
                }

                if (active.isNotEmpty()) {
                    item { Text("En cours", color = Lime, modifier = Modifier.padding(top = 8.dp)) }
                    items(active, key = { "run" + it.id }) { job ->
                        JobRow(job, reduced, onCancel = { YouTubeDownloads.cancel(context, job.id) }, onRetry = null)
                    }
                }

                if (failed.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Échecs", color = Lime, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                failed.forEach {
                                    YouTubeDownloads.retry(context, it.id, youtubeAudioQuality(prefs),
                                        youtubeVideoQuality(prefs), youtubeDownloadFormat(prefs))
                                }
                            }) { Text("Tout relancer") }
                        }
                    }
                    items(failed, key = { "fail" + it.id }) { job ->
                        JobRow(job, reduced, onCancel = { YouTubeDownloads.forget(job.id) }, onRetry = {
                            YouTubeDownloads.retry(context, job.id, youtubeAudioQuality(prefs),
                                youtubeVideoQuality(prefs), youtubeDownloadFormat(prefs))
                        })
                    }
                }

                if (archive.isNotEmpty()) {
                    item { Text("Internet Archive", color = Lime, modifier = Modifier.padding(top = 8.dp)) }
                    items(archive, key = { "ia" + it.download.id }) { state ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.download.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                when (state.status) {
                                    DownloadManager.STATUS_RUNNING ->
                                        if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    DownloadManager.STATUS_SUCCESSFUL -> Text("Terminé", color = Muted, fontSize = 11.sp)
                                    DownloadManager.STATUS_PAUSED -> Text("En pause · attente du réseau", color = Muted, fontSize = 11.sp)
                                    DownloadManager.STATUS_FAILED -> Text("Échec", color = Muted, fontSize = 11.sp)
                                    else -> Text("En attente…", color = Muted, fontSize = 11.sp)
                                }
                            }
                            if (state.status != DownloadManager.STATUS_SUCCESSFUL) IconButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { FreeDownloads.cancel(context, state.download.id) } }
                            }) { Icon(Icons.Rounded.Close, "Annuler ce téléchargement") }
                        }
                    }
                }

                if (youtube.any { it.finished }) item {
                    TextButton(onClick = { YouTubeDownloads.clearFinished() }) { Text("Effacer la liste des terminés") }
                }
            }
        }
    }
    if (torrentsOpen) TorrentScreen(vm) { torrentsOpen = false }
}

@Composable
private fun JobRow(job: YouTubeDownload, reduced: Boolean, onCancel: () -> Unit, onRetry: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = if (onRetry != null) Muted else Lime)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(job.title, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (job.state) {
                DownloadState.QUEUED -> Text("En attente…", color = Muted, fontSize = 11.sp)
                DownloadState.PREPARING -> Text("Préparation du moteur…", color = Muted, fontSize = 11.sp)
                DownloadState.UPDATING -> Text("Mise à jour de yt-dlp…", color = Muted, fontSize = 11.sp)
                DownloadState.CONVERTING -> Text("Conversion et pochette…", color = Muted, fontSize = 11.sp)
                DownloadState.FAILED -> Text(job.error.ifBlank { "Échec" }, color = Muted, fontSize = 11.sp)
                DownloadState.CANCELLED -> Text("Annulé", color = Muted, fontSize = 11.sp)
                DownloadState.DONE -> Text("Terminé", color = Muted, fontSize = 11.sp)
                DownloadState.RUNNING ->
                    if (job.progress > 0f) LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (onRetry != null) IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Relancer ${job.title}", tint = Lime) }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, if (onRetry != null) "Retirer de la liste" else "Annuler") }
    }
}
