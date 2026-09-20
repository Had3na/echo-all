package fr.nacre.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.session.MediaController

@Composable
fun CollectionsScreen(library: List<LibraryItem>, player: MediaController?, onPhotos: (List<String>) -> Unit, onDismiss: () -> Unit, initialId: String? = null) {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    val (_, prefs) = rememberPreferences()
    var lists by remember { mutableStateOf(store.lists()) }
    var selectedId by rememberSaveable { mutableStateOf(initialId ?: store.lists().firstOrNull()?.id.orEmpty()) }
    var current by remember { mutableStateOf(store.lists().find { it.id == selectedId } ?: store.newList("Ma playlist", emptyList())) }
    val cover = rememberCover("list:" + current.id)
    var coverMessage by remember { mutableStateOf("") }
    val chooseCover = rememberCoverPicker("list:" + current.id) { coverMessage = it }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("Tous") }
    var message by remember { mutableStateOf("") }
    var deleted by remember { mutableStateOf<SavedList?>(null) }
    var smartMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val byUri = remember(library) { library.associateBy { it.uri } }
    fun update(value: SavedList) { current = value; selectedId = value.id; store.saveList(value); lists = store.lists() }
    // Create immediately, including an empty playlist, so it is visible on Home after closing.
    LaunchedEffect(Unit) { if (lists.none { it.id == current.id }) update(current) }
    fun start(shuffle: Boolean = current.shuffle, selection: List<LibraryItem>? = null) {
        val entries = collectionOrder(selection ?: current.uris.mapNotNull { byUri[it] }, shuffle)
        val playable = entries.filter { it.kind != MediaKind.PHOTO }
        if (entries.isEmpty()) { message = "Ajoute des médias dans l’onglet Ajouter, ou relance le scan si les fichiers sont indisponibles."; return }
        if (playable.isEmpty()) { onPhotos(entries.map { it.uri }); onDismiss(); return }
        val controller = player ?: run { message = "Le lecteur démarre… Réessaie dans un instant."; return }
        prefs.edit().putString("mixStyle", current.style).putInt("mixSeconds", current.seconds).apply()
        controller.shuffleModeEnabled = false
        controller.setMediaItems(playable.map { it.playable() }); controller.prepare(); controller.play()
        message = "Lecture de ${playable.size} médias." + if (entries.any { it.kind == MediaKind.PHOTO }) " Les photos sont accessibles avec le bouton Album." else ""
        if (playable.first().kind == MediaKind.VIDEO) {
            (context as? MainActivity)?.externalVideo = true
            context.startActivity(android.content.Intent(context, VideoActivity::class.java))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 12.dp).size(56.dp).clip(RoundedCornerShape(14.dp)).background(Panel), contentAlignment = Alignment.Center) {
                        if (cover != null) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else current.uris.firstNotNullOfOrNull { byUri[it] }?.let { MediaThumbnail(it, Modifier.fillMaxSize()) } ?: Icon(Icons.Rounded.LibraryMusic, null, tint = Lime)
                    }
                    Column(Modifier.weight(1f)) { Text(current.name.ifBlank { "Sans titre" }, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${current.uris.size} titres", color = Muted, fontSize = 11.sp) }
                    Box {
                        IconButton(onClick = { smartMenu = true }) { Icon(Icons.Rounded.AutoAwesome, "Sélections intelligentes") }
                        DropdownMenu(smartMenu, { smartMenu = false }) {
                            listOf("Favoris", "Jamais lus", "Plus joués", "Récents").forEach { mode -> DropdownMenuItem(text = { Text(mode) }, onClick = {
                                smartMenu = false
                                val counts = if (mode == "Jamais lus" || mode == "Plus joués") store.counts() else emptyMap()
                                start(selection = when (mode) { "Favoris" -> library.filter { it.favorite }; "Jamais lus" -> library.filter { it.kind != MediaKind.PHOTO && (counts[it.uri] ?: 0) == 0 }; "Plus joués" -> library.filter { (counts[it.uri] ?: 0) > 0 }.sortedByDescending { counts[it.uri] }; else -> library.sortedByDescending { it.addedAt }.take(50) })
                            }) }
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update(store.newList("Nouvelle playlist", emptyList())); tab = 1 }) { Text("Nouvelle") }
                    lists.forEach { list -> FilterChip(current.id == list.id, { current = list; selectedId = list.id; message = "" }, { Text(list.name.ifBlank { "Sans titre" }, maxLines = 1) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { start() }, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Écouter") }
                    FilterChip(current.shuffle, { update(current.copy(shuffle = !current.shuffle)) }, { Text(if (current.shuffle) "Aléatoire activé" else "Aléatoire désactivé") })
                    if (current.uris.any { byUri[it]?.kind == MediaKind.PHOTO }) OutlinedButton(onClick = { onPhotos(current.uris.filter { byUri[it]?.kind == MediaKind.PHOTO }); onDismiss() }) { Text("Album") }
                }
                TabRow(selectedTabIndex = tab) {
                    listOf("Médias", "Ajouter", "Mix DJ", "Réglages").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, fontSize = 12.sp, maxLines = 1) }) }
                }
                if (coverMessage.isNotBlank()) Text(coverMessage, color = Lime)
                if (message.isNotBlank()) Text(message, color = Lime, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                deleted?.let { removed -> TextButton(onClick = { update(removed); deleted = null }) { Text("Annuler la suppression de ${removed.name}") } }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    when (tab) {
                        0 -> {
                            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Les titres", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                Text("${current.uris.size}", color = Muted)
                            } }
                            if (current.uris.isEmpty()) item { OutlinedButton(onClick = { tab = 1 }) { Text("Ajouter les premiers titres") } }
                            itemsIndexed(current.uris, key = { _, uri -> uri }) { index, uri ->
                                val item = byUri[uri]
                                var menu by remember(uri) { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = item != null) {
                                    start(shuffle = false, selection = current.uris.drop(index).mapNotNull { byUri[it] })
                                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
                                        if (item != null) MediaThumbnail(item, Modifier.fillMaxSize()) else Icon(Icons.Rounded.MusicOff, null, tint = Muted)
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item?.title ?: "Fichier indisponible", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text(item?.artist?.ifBlank { item.album.ifBlank { "Titre ${index + 1}" } } ?: "Relancer le scan", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (item != null && item.durationMs > 0) Text(mediaClock(item.durationMs), color = Muted, fontSize = 11.sp)
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options du titre") }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text("Monter") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, enabled = index > 0, onClick = { val next = current.uris.toMutableList(); java.util.Collections.swap(next,index,index-1); update(current.copy(uris=next)); menu=false })
                                            DropdownMenuItem(text = { Text("Descendre") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, enabled = index < current.uris.lastIndex, onClick = { val next = current.uris.toMutableList(); java.util.Collections.swap(next,index,index+1); update(current.copy(uris=next)); menu=false })
                                            DropdownMenuItem(text = { Text("Retirer de la playlist") }, leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, null) }, onClick = { update(current.copy(uris=current.uris-uri)); menu=false })
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            val results = library.filter { (it.title + " " + it.artist).contains(filter, true) && (type == "Tous" || it.kind.name == type) }
                            item { OutlinedTextField(filter, { filter = it }, label = { Text("Titre ou artiste") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                            item { Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("Tous" to "Tous", "MUSIC" to "Musique", "VIDEO" to "Vidéos", "PHOTO" to "Photos").forEach { (id, title) -> FilterChip(type == id, { type = id }, { Text(title) }) } } }
                            item { Text("${results.size} résultats", color = Muted); TextButton(onClick = { update(current.copy(uris = (current.uris + results.map { it.uri }).distinct())) }) { Text("Ajouter tous les résultats") } }
                            items(results, key = { it.uri }) { item -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(item.uri in current.uris, { checked -> update(current.copy(uris = if (checked) current.uris + item.uri else current.uris - item.uri)) })
                                Text(item.title, maxLines = 2, modifier = Modifier.weight(1f))
                            } }
                        }
                        2 -> {
                            item { Text("Transitions de cette playlist", fontSize = 20.sp); Text("Ce profil est appliqué lorsque tu lances cette playlist. Les commandes avancées restent dans le studio DJ du lecteur.", color = Muted) }
                            item { Column { listOf("club" to "Club · échange des basses", "sweep" to "Filtre · ouverture progressive", "smooth" to "Doux · fondu progressif", "linear" to "Linéaire · mélange régulier", "cut" to "Net · passage franc").forEach { (id, title) -> FilterChip(current.style == id, { update(current.copy(style = id)) }, { Text(title) }) } } }
                            item { Text(if (current.seconds == 0) "Transition désactivée" else "Durée : ${current.seconds} secondes"); Slider(current.seconds.toFloat(), { update(current.copy(seconds = it.toInt())) }, valueRange = 0f..60f, steps = 59) }
                        }
                        3 -> {
                            item { Text("Image de la playlist", fontSize = 20.sp) }
                            item { if (cover != null) coil.compose.AsyncImage(cover, "Image de playlist", Modifier.fillMaxWidth().height(180.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            item { Row { OutlinedButton(onClick = chooseCover) { Text("Choisir une image") }; if (cover != null) TextButton(onClick = { clearCover(context, "list:" + current.id) }) { Text("Retirer") } } }
                            item { Text("Identité de la playlist", fontSize = 20.sp) }
                            item { OutlinedTextField(current.name, { update(current.copy(name = it.take(80))) }, label = { Text("Nom") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                            item { Text("Les changements sont enregistrés tout de suite et se retrouvent sur l’accueil.", color = Muted) }
                            item { OutlinedButton(onClick = { val copy = store.newList(current.name + " · copie", current.uris).copy(style = current.style, seconds = current.seconds, shuffle = current.shuffle); val source = "list:" + current.id; Covers.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Covers.duplicate(context, source, "list:" + copy.id) } } }; update(copy); message = "Copie créée." }) { Text("Dupliquer cette playlist") } }
                            item { TextButton(onClick = { confirmDelete = true }) { Text("Supprimer cette playlist", color = MaterialTheme.colorScheme.error) } }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer cette playlist ?") }, text = { Text("Les fichiers du téléphone seront conservés.") },
        confirmButton = { TextButton(onClick = { deleted = current; store.deleteList(current.id); lists = store.lists(); current = lists.firstOrNull() ?: store.newList("Ma playlist", emptyList()); selectedId = current.id; confirmDelete = false }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } })
}
