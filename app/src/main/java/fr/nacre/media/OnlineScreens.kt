package fr.nacre.media

import android.Manifest
import android.app.DownloadManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun duration(ms: Long) = if (ms <= 0) "" else "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
private fun megabytes(bytes: Long) = if (bytes <= 0) "" else "%.1f Mo".format(bytes / 1_048_576.0)

@Composable
private fun Thumb(url: String, size: Int, fallback: androidx.compose.ui.graphics.vector.ImageVector) {
    val placeholder = rememberVectorPainter(fallback)
    AsyncImage(url, null, Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Panel), contentScale = ContentScale.Crop,
        placeholder = placeholder, error = placeholder, fallback = placeholder)
}

/** Finds title, artist, album and cover for a track on MusicBrainz / Cover Art Archive. */
@Composable
fun TagDialog(item: LibraryItem, onApply: (TagCandidate, Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    val guess = remember(item.uri) { guessTags(item.title, item.artist) }
    var title by remember(item.uri) { mutableStateOf(guess.first) }
    var artist by remember(item.uri) { mutableStateOf(guess.second) }
    var results by remember { mutableStateOf<List<TagCandidate>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var withText by remember { mutableStateOf(true) }
    var withCover by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (title.isBlank() || loading) return
        loading = true; error = ""
        scope.launch {
            try { results = Online.findTags(title, artist); if (results.isNullOrEmpty()) error = "Aucun résultat. Essaie sans l’artiste ou corrige le titre." }
            catch (_: Exception) { error = "Recherche impossible. Vérifie ta connexion." }
            finally { loading = false }
        }
    }
    LaunchedEffect(item.uri) { search() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pochette et informations", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                } }
                item { Text("Fichier : ${item.title}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                item { OutlinedTextField(title, { title = it }, label = { Text("Titre") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(artist, { artist = it }, label = { Text("Artiste (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() })) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { search() }, enabled = title.isNotBlank() && !loading) { Icon(Icons.Rounded.Search, null); Text(if (loading) " Recherche…" else " Rechercher") }
                } }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withText, { withText = it }); Text("Titre, artiste, album", Modifier.weight(1f))
                    Checkbox(withCover, { withCover = it }); Text("Pochette")
                } }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (error.isNotBlank()) item { Text(error, color = Lime) }
                items(results.orEmpty(), key = { it.releaseId + it.title + it.score }) { match ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable(enabled = withText || withCover) { onApply(match, withText, withCover); onDismiss() }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Thumb(match.thumbnail, 56, Icons.Rounded.Album)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(match.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(match.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(listOf(match.album, match.year, duration(match.lengthMs)).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${match.score} %", color = if (match.score >= 90) Lime else Muted, fontSize = 12.sp)
                    }
                }
                item { Text("Données MusicBrainz et Cover Art Archive. Touche un résultat pour l’appliquer.", color = Muted, fontSize = 11.sp) }
            }
        }
    }
}

/** Search and download of public-domain / Creative Commons music and videos from the Internet Archive. */
@Composable
fun FreeMediaScreen(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(MediaKind.MUSIC) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArchiveResult>?>(null) }
    var selected by remember { mutableStateOf<ArchiveResult?>(null) }
    var item by remember { mutableStateOf<ArchiveItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(emptyList<DownloadProgress>()) }
    var pendingFiles by remember { mutableStateOf<List<ArchiveFile>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val states = withContext(Dispatchers.IO) { runCatching { FreeDownloads.progress(context) }.getOrDefault(emptyList()) }
            progress = states
            if (states.any { it.status == DownloadManager.STATUS_SUCCESSFUL || it.status == DownloadManager.STATUS_FAILED }) vm.collectDownloads()
            delay(1_000)
        }
    }
    fun download(files: List<ArchiveFile>) {
        val current = item ?: return
        scope.launch {
            val started = withContext(Dispatchers.IO) { files.count { file -> runCatching { FreeDownloads.enqueue(context, current, file, kind) }.isSuccess } }
            message = if (started == files.size) (if (started == 1) "Téléchargement lancé." else "$started téléchargements lancés.") else "Certains téléchargements n’ont pas pu démarrer."
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) download(pendingFiles) else message = "Autorisation de stockage refusée : téléchargement impossible sur cette version d’Android."
    }
    fun requestDownload(files: List<ArchiveFile>) {
        if (FreeDownloads.needsStoragePermission && !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { pendingFiles = files; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else download(files)
    }
    fun search() {
        if (loading) return
        loading = true; message = ""; selected = null; item = null
        scope.launch {
            try { results = Online.searchArchive(query, kind); if (results.isNullOrEmpty()) message = "Aucun résultat libre pour cette recherche." }
            catch (_: Exception) { message = "Recherche impossible. Vérifie ta connexion." }
            finally { loading = false }
        }
    }
    fun open(result: ArchiveResult) {
        selected = result; item = null; loading = true; message = ""
        scope.launch {
            try { item = Online.archiveItem(result.identifier, kind); if (item?.files.isNullOrEmpty()) message = "Aucun fichier ${if (kind == MediaKind.VIDEO) "vidéo" else "audio"} téléchargeable dans cet élément." }
            catch (_: Exception) { message = "Impossible d’ouvrir cet élément. Réessaie." }
            finally { loading = false }
        }
    }

    Dialog(onDismissRequest = { if (selected != null) { selected = null; item = null } else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) IconButton(onClick = { selected = null; item = null; message = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour aux résultats") }
                    Text("Musique et vidéos libres", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                } }
                if (progress.isNotEmpty()) {
                    item { Text("Téléchargements", color = Lime) }
                    items(progress, key = { "dl" + it.download.id }) { state ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.download.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                when (state.status) {
                                    DownloadManager.STATUS_RUNNING -> if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    DownloadManager.STATUS_SUCCESSFUL -> Text("Terminé · ajout à la bibliothèque…", color = Muted, fontSize = 11.sp)
                                    DownloadManager.STATUS_PAUSED -> Text("En pause · attente du réseau", color = Muted, fontSize = 11.sp)
                                    DownloadManager.STATUS_FAILED -> Text("Échec", color = Muted, fontSize = 11.sp)
                                    else -> Text("En attente…", color = Muted, fontSize = 11.sp)
                                }
                            }
                            if (state.status != DownloadManager.STATUS_SUCCESSFUL) IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { FreeDownloads.cancel(context, state.download.id) } } }) { Icon(Icons.Rounded.Close, "Annuler ce téléchargement") }
                        }
                    }
                }
                val open = selected
                if (open == null) {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind == MediaKind.MUSIC, { kind = MediaKind.MUSIC; results = null }, { Text("Musique") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp)) })
                        FilterChip(kind == MediaKind.VIDEO, { kind = MediaKind.VIDEO; results = null }, { Text("Vidéo") }, leadingIcon = { Icon(Icons.Rounded.Movie, null, Modifier.size(16.dp)) })
                    } }
                    item { OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        placeholder = { Text(if (kind == MediaKind.VIDEO) "Film, documentaire, dessin animé…" else "Artiste, genre, album…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() })) }
                    item { Button(onClick = { search() }, enabled = !loading) { Text(if (loading) "Recherche…" else "Rechercher") } }
                    item { Text("Domaine public et licences Creative Commons, hébergés par l’Internet Archive. Respecte la licence affichée : NC = pas d’usage commercial, ND = pas de modification, BY = citer l’auteur.", color = Muted, fontSize = 11.sp) }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Text(message, color = Lime) }
                    items(results.orEmpty(), key = { it.identifier }) { result ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable { open(result) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(result.thumbnail, 64, if (kind == MediaKind.VIDEO) Icons.Rounded.Movie else Icons.Rounded.MusicNote)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(result.creator, result.year).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                Text(result.license, color = Lime, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    item { Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(open.thumbnail, 96, if (kind == MediaKind.VIDEO) Icons.Rounded.Movie else Icons.Rounded.Album)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(item?.title ?: open.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item?.creator ?: open.creator, item?.year ?: open.year).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp)
                            Text(item?.license ?: open.license, color = Lime, fontSize = 12.sp)
                        }
                    } }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Text(message, color = Lime) }
                    val files = item?.files.orEmpty()
                    if (files.size > 1 && files.size <= 60) item {
                        FilledTonalButton(onClick = { requestDownload(files) }) { Icon(Icons.Rounded.Download, null); Text(" Tout télécharger (${files.size} · ${megabytes(files.sumOf { it.sizeBytes })})") }
                    }
                    items(files, key = { it.name }) { file ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (file.track > 0) "${file.track}. " else "") + file.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(file.artist, duration(file.durationMs), file.extension.uppercase(), megabytes(file.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { requestDownload(listOf(file)) }) { Icon(Icons.Rounded.Download, "Télécharger ${file.title}", tint = Lime) }
                        }
                    }
                    if (files.isNotEmpty()) item { Text("Enregistré dans ${if (kind == MediaKind.VIDEO) "Films" else "Musique"}/Echo-All, ajouté à ta bibliothèque avec titre, artiste, album et pochette.", color = Muted, fontSize = 11.sp) }
                }
            }
        }
    }
}
