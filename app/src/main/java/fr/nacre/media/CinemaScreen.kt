@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.nacre.media

import android.Manifest
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun clock(ms: Long) = if (ms <= 0) "" else mediaClock(ms)
private fun megabytes(bytes: Long) = if (bytes <= 0) "" else "%.0f Mo".format(bytes / 1_048_576.0)

/**
 * The cinema: a shelf per genre, a film on the front, and a sheet that tells you what it is before
 * you commit to it. The flat list of search results it replaces made you read licence strings to
 * find out whether something was worth a tap.
 */
@Composable
fun CinemaScreen(vm: LibraryViewModel, prefs: SharedPreferences, onPlay: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = remember(prefs) { prefs.getBoolean("reduceMotion", false) }

    var torrentSearch by rememberSaveable { mutableStateOf(true) }
    var discovery by remember { mutableStateOf(false) }
    var shelves by remember { mutableStateOf<Map<String, List<ArchiveResult>>>(emptyMap()) }
    var featured by remember { mutableStateOf<ArchiveResult?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArchiveResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<ArchiveResult?>(null) }
    var message by remember { mutableStateOf("") }

    // Every shelf at once: seven small queries in parallel beat seven seconds of waiting in turn.
    LaunchedEffect(Unit) {
        val loaded = CINEMA_ROWS.map { row ->
            async(Dispatchers.IO) { row.label to runCatching { Online.archiveShelf(row) }.getOrDefault(emptyList()) }
        }.awaitAll().toMap()
        shelves = loaded
        featured = loaded.values.flatten().firstOrNull { it.description.length > 80 }
            ?: loaded.values.flatten().firstOrNull()
        if (loaded.values.all { it.isEmpty() }) message = "Catalogue injoignable. Vérifie ta connexion."
    }

    fun search() {
        val text = query.trim()
        if (text.isBlank()) { results = null; return }
        keyboard?.hide()
        searching = true; message = ""
        scope.launch {
            try {
                results = Online.searchArchive(text, MediaKind.VIDEO)
                if (results.isNullOrEmpty()) message = "Aucun film libre pour cette recherche."
            } catch (_: Exception) { message = "Recherche impossible. Vérifie ta connexion." }
            finally { searching = false }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(bottom = 32.dp)) {

                item {
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Films", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                    }
                }

                item {
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(torrentSearch, { torrentSearch = true }, label = { Text("Torrents") })
                        FilterChip(!torrentSearch, { torrentSearch = false }, label = { Text("Archives") })
                    }
                }
                if (torrentSearch) item { Column(Modifier.padding(horizontal = 18.dp)) { TorrentSearchPanel() } }
                if (!torrentSearch) item {
                    OutlinedTextField(query, { query = it; if (it.isBlank()) results = null },
                        singleLine = true, shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        placeholder = { Text("Un titre, un réalisateur, un genre…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                }

                item { Button(onClick = { discovery = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) { Text("Explorer films, séries et animes") } }

                if (message.isNotBlank()) item {
                    Text(message, color = Lime, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp))
                }

                val found = results
                if (found != null || searching) {
                    item {
                        Text(if (searching) "Recherche…" else "${found?.size ?: 0} résultats",
                            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    if (searching) item {
                        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(3) { Box(Modifier.weight(1f).height(96.dp).shimmer(reduced, 14)) }
                        }
                    } else item {
                        // A grid rather than a row: a search wants everything visible at once.
                        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 2_000.dp).padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            userScrollEnabled = false) {
                            items(found.orEmpty(), key = { it.identifier }) { film ->
                                PosterCard(film, reduced, 150, 0) { open = film }
                            }
                        }
                    }
                } else {
                    featured?.let { hero -> item { Hero(hero, reduced) { open = hero } } }
                    CINEMA_ROWS.forEach { row ->
                        val films = shelves[row.label]
                        if (films == null || films.isNotEmpty()) {
                            item(key = "row:" + row.label) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(row.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 18.dp))
                                    if (films == null) Row(Modifier.padding(horizontal = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        repeat(3) { Box(Modifier.width(150.dp).height(96.dp).shimmer(reduced, 14)) }
                                    }
                                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(horizontal = 18.dp)) {
                                        itemsIndexed(films, key = { _, film -> row.label + film.identifier }) { index, film ->
                                            PosterCard(film, reduced, 150, index) { open = film }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text("Domaine public et licences Creative Commons, hébergés par l’Internet Archive. " +
                            "Respecte la licence affichée sur chaque film.",
                            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                }
            }
        }
    }

    if (discovery) CinemaDiscovery(onPlay) { discovery = false }
    open?.let { film -> FilmSheet(film, vm, prefs, onPlay, { open = null }) }
}

@Composable
private fun Hero(film: ArchiveResult, reduced: Boolean, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val placeholder = rememberVectorPainter(Icons.Rounded.Movie)
    Box(Modifier.fillMaxWidth().height(300.dp).padding(horizontal = 18.dp)
        .pressScale(interaction, reduced)
        .clip(RoundedCornerShape(24.dp)).background(Panel)
        .clickable(interaction, null, onClick = onOpen)) {
        AsyncImage(film.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            placeholder = placeholder, error = placeholder, fallback = placeholder)
        // The text sits on the picture, so the picture has to darken under it.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Ink.copy(alpha = .55f), Ink.copy(alpha = .95f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("À L’AFFICHE", color = Lime, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(film.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(film.creator, film.year, film.license).filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (film.description.isNotBlank()) Text(film.description, color = Muted, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Button(onClick = onOpen, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Rounded.PlayArrow, null); Text(" Regarder")
            }
        }
    }
}

@Composable
private fun PosterCard(film: ArchiveResult, reduced: Boolean, width: Int, index: Int, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val placeholder = rememberVectorPainter(Icons.Rounded.Movie)
    Column(Modifier.width(width.dp).entrance(index, reduced, film.identifier).pressScale(interaction, reduced)
        .clickable(interaction, null, onClick = onOpen), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height((width * .62f).dp).clip(RoundedCornerShape(14.dp)).background(Panel)) {
            AsyncImage(film.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                placeholder = placeholder, error = placeholder, fallback = placeholder)
        }
        Text(film.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(film.year, film.creator).filter { it.isNotBlank() }.joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What a film is, before committing to it: synopsis, licence, and the versions on offer. */
@Composable
private fun FilmSheet(film: ArchiveResult, vm: LibraryViewModel, prefs: SharedPreferences,
                      onPlay: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduced = remember(prefs) { prefs.getBoolean("reduceMotion", false) }
    var item by remember(film.identifier) { mutableStateOf<ArchiveItem?>(null) }
    var failed by remember(film.identifier) { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<ArchiveFile>>(emptyList()) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(film.identifier) {
        try { item = Online.archiveItem(film.identifier, MediaKind.VIDEO) }
        catch (_: Exception) { failed = "Impossible d’ouvrir cette fiche. Réessaie." }
    }

    fun download(files: List<ArchiveFile>) {
        val current = item ?: return
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                files.count { runCatching { FreeDownloads.enqueue(context, current, it, MediaKind.VIDEO) }.isSuccess }
            }
            note = if (started > 0) "Téléchargement lancé." else "Téléchargement impossible."
        }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val waiting = pending; pending = emptyList()
        if (granted) download(waiting) else note = "Autorisation refusée : enregistrement impossible."
    }
    fun requestDownload(files: List<ArchiveFile>) {
        if (FreeDownloads.needsStoragePermission && !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pending = files; storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else download(files)
    }

    val placeholder = rememberVectorPainter(Icons.Rounded.Movie)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(230.dp)) {
                        AsyncImage(film.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            placeholder = placeholder, error = placeholder, fallback = placeholder)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            listOf(Ink.copy(alpha = .35f), Color.Transparent, Ink))))
                        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour")
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item?.title ?: film.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(listOf(item?.creator ?: film.creator, item?.year ?: film.year, clock(item?.runtime ?: 0))
                            .filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
                        Text(item?.license ?: film.license, color = Lime, fontSize = 12.sp)
                    }
                }

                val files = item?.files.orEmpty()
                val main = files.maxByOrNull { it.sizeBytes }
                if (main != null) item {
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onPlay(main.url, main.title.ifBlank { film.title }); onDismiss() },
                            modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Text(" Regarder") }
                        FilledTonalButton(onClick = { requestDownload(listOf(main)) }) {
                            Icon(Icons.Rounded.Download, null); Text(" Garder")
                        }
                    }
                }

                if (note.isNotBlank()) item { Text(note, color = Lime, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp)) }
                if (failed.isNotBlank()) item { Text(failed, color = Lime, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp)) }
                if (item == null && failed.isBlank()) item {
                    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { Box(Modifier.fillMaxWidth().height(16.dp).shimmer(reduced, 6)) }
                    }
                }

                val synopsis = item?.description.orEmpty().ifBlank { film.description }
                if (synopsis.isNotBlank()) item {
                    Text(synopsis, color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 18.dp))
                }

                // More than one file usually means several encodings, or the episodes of a serial.
                if (files.size > 1) {
                    item {
                        Text("Autres versions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                    }
                    items(files, key = { it.name }) { file ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Panel)
                            .clickable { onPlay(file.url, file.title.ifBlank { film.title }); onDismiss() }
                            .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.title.ifBlank { file.name }, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(file.extension.uppercase(), clock(file.durationMs), megabytes(file.sizeBytes))
                                    .filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { requestDownload(listOf(file)) }) {
                                Icon(Icons.Rounded.Download, "Télécharger", tint = Lime)
                            }
                        }
                    }
                }
            }
        }
    }
}
