@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.nacre.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import android.view.LayoutInflater

class MainActivity : ComponentActivity() {
    var externalVideo = false
    /** A link shared from the YouTube app, or a youtube.com address opened with Echo-All. */
    val sharedLink = androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun onResume() { super.onResume(); externalVideo = false }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        takeLink(intent)
        setContent { NacreTheme { NacreApp() } }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
    }
    private fun takeLink(intent: android.content.Intent?) {
        val text = intent?.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: intent?.dataString.orEmpty()
        youtubeLinkIn(text)?.let { sharedLink.value = it }
    }
}

private enum class Page(val label: String, val icon: ImageVector, val kind: MediaKind? = null) {
    HOME("Accueil", Icons.Rounded.Home),
    MUSIC("Musique", Icons.Rounded.MusicNote, MediaKind.MUSIC),
    VIDEO("Vidéos", Icons.Rounded.PlayCircle, MediaKind.VIDEO),
    PHOTO("Photos", Icons.Rounded.Photo, MediaKind.PHOTO),
    SOURCES("Sources", Icons.Rounded.Storage)
}

@Composable
private fun rememberPlayer(onError: (String) -> Unit): MediaController? {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaController?>(null) }
    val error by rememberUpdatedState(onError)
    DisposableEffect(context) {
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) {
                try { player = future.get() }
                catch (_: Exception) { error("Le lecteur n’a pas pu démarrer. Relance Echo-All.") }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }
    return player
}

private data class PlaybackState(val title: String = "", val id: String = "", val playing: Boolean = false,
    val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0, val video: Boolean = false, val shuffle: Boolean = false, val repeat: Int = 0, val speed: Float = 1f)

@Composable
private fun playbackState(player: Player?, onError: (String) -> Unit): PlaybackState {
    var state by remember { mutableStateOf(PlaybackState()) }
    val error by rememberUpdatedState(onError)
    fun refresh() {
        state = PlaybackState(player?.mediaMetadata?.title?.toString().orEmpty(),
            player?.currentMediaItem?.mediaId.orEmpty(), player?.isPlaying == true,
            player?.playbackState == Player.STATE_BUFFERING,
            (player?.currentPosition ?: 0).coerceAtLeast(0), (player?.duration ?: 0).coerceAtLeast(0), player?.mediaMetadata?.extras?.getBoolean("video") == true, player?.shuffleModeEnabled == true, player?.repeatMode ?: 0, player?.playbackParameters?.speed ?: 1f)
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { refresh() }
            override fun onPlayerError(exception: PlaybackException) {
                error("Lecture impossible. Vérifie le fichier, le lien et ta connexion, puis réessaie.")
            }
        }
        player?.addListener(listener)
        refresh()
        onDispose { player?.removeListener(listener) }
    }
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(player, lifecycle) {
        while (player != null && lifecycle.isAtLeast(Lifecycle.State.STARTED)) { refresh(); delay(500) }
    }
    return state
}

@Composable
private fun NacreApp(vm: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val (settings, prefs) = rememberPreferences()
    StartupUpdateNotice(prefs)
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionsOpen by rememberSaveable { mutableStateOf(false) }
    var albumPhotos by remember { mutableStateOf<List<String>?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Récents") }
    var folder by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val library by vm.items.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var streamDialog by rememberSaveable { mutableStateOf(false) }
    var freeMediaOpen by rememberSaveable { mutableStateOf(false) }
    var radiosOpen by rememberSaveable { mutableStateOf(false) }
    var youtubeOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var cinemaOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var playlistPicker by remember { mutableStateOf(false) }
    val sharedLink = (context as? MainActivity)?.sharedLink
    LaunchedEffect(sharedLink?.value) { if (!sharedLink?.value.isNullOrBlank()) youtubeOpen = true }
    var musicTab by rememberSaveable { mutableStateOf("tracks") }
    var undoItem by remember { mutableStateOf<LibraryItem?>(null) }
    var videoSection by rememberSaveable { mutableStateOf("daily") }
    var videoCategory by rememberSaveable { mutableStateOf("Toutes") }
    var organizeItem by remember { mutableStateOf<LibraryItem?>(null) }
    var tagItem by remember { mutableStateOf<LibraryItem?>(null) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var photoId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(92.dp) }
    val player = rememberPlayer { vm.message.value = it }
    val playback = playbackState(player) { vm.message.value = it }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importMedia(it) }
    val importMedia: () -> Unit = { importer.launch(arrayOf("audio/*", "video/*", "image/*")) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) prefs.edit().putBoolean("autoScan", true).apply()
        vm.scan()
    }
    val scan: () -> Unit = { permissionLauncher.launch(scanPermissions()) }
    LaunchedEffect(Unit) {
        vm.busy.first { !it }
        if (prefs.getBoolean("autoScan", false)) vm.scan(silent = true)
    }
    val visible = remember(library, query, page, favorites, sort, folder, videoSection, videoCategory) {
        val filtered = library.filter { (page.kind == null || it.kind == page.kind) && (!favorites || it.favorite) && (page != Page.VIDEO || (it.videoSpace() == videoSection && (videoCategory == "Toutes" || it.videoGroup() == videoCategory)))
            && (folder.isBlank() || (if (folder.startsWith("Artiste · ")) it.artist == folder.removePrefix("Artiste · ") else if (folder.startsWith("Album · ")) it.album == folder.removePrefix("Album · ") else it.folder == folder)) && (it.title + " " + it.artist + " " + it.album + " " + it.folder).contains(query, true) }
        when (sort) {
            "Nom A–Z" -> filtered.sortedBy { it.title.lowercase() }
            "Durée" -> filtered.sortedByDescending { it.durationMs }
            else -> filtered.sortedByDescending { it.addedAt }
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(notice) {
        notice?.let { pending ->
            val answer = snackbar.showSnackbar(pending.text, "Annuler", duration = SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) pending.undo()
            vm.notice.value = null
        }
    }

    fun play(item: LibraryItem, selection: List<LibraryItem>? = null) {
        if (item.kind == MediaKind.PHOTO) { photoId = item.uri; return }
        val controller = player ?: run { vm.message.value = "Le lecteur se prépare…"; return }
        if (controller.currentMediaItem?.mediaId == item.uri && selection == null) {
            if (controller.playerError != null) controller.prepare()
            if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0)
            controller.play()
        } else {
            val queue = selection ?: visible.filter { it.kind == item.kind }.let { if(it.any { entry -> entry.uri == item.uri }) it else listOf(item) }
            val playbackPrefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
            val storedPosition = playbackPrefs.getLong(item.uri, 0)
            val position = if(selection != null) 0L else if(item.kind == MediaKind.VIDEO) videoResumePosition(storedPosition, playbackPrefs.getLong("duration:" + item.uri, item.durationMs)) else storedPosition
            val queueMatches = controller.mediaItemCount == queue.size && queue.indices.all { controller.getMediaItemAt(it).mediaId == queue[it].uri }
            if (queueMatches) controller.seekTo(queue.indexOf(item).coerceAtLeast(0), position)
            else controller.setMediaItems(queue.map { it.playable() }, queue.indexOf(item).coerceAtLeast(0), position)
            controller.prepare()
            controller.play()
        }
        if (item.kind == MediaKind.VIDEO) { (context as? MainActivity)?.externalVideo = true; context.startActivity(android.content.Intent(context, VideoActivity::class.java)) }
    }

    val owner = LocalLifecycleOwner.current
    val videoPlaying by rememberUpdatedState(playback.video)
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && videoPlaying && (context as? MainActivity)?.externalVideo != true) player?.pause()
            if (event == Lifecycle.Event.ON_RESUME && prefs.getBoolean("autoScan", false)) vm.scan(silent = true)
            if (event == Lifecycle.Event.ON_RESUME) { vm.collectDownloads(); vm.collectYouTube() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },

    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).then(pageEntrance(page, settings.reduceMotion))) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(R.drawable.echo_logo, "Logo Echo-All", Modifier.size(42.dp).clip(CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("echo-all", fontSize = 20.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.Bold)
                    Text(if (page == Page.HOME) "À ton rythme." else page.label,
                        fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = { collectionsOpen = true }) { Icon(Icons.Rounded.LibraryMusic, "Collections") }
                IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.Tune, "Personnaliser Echo-All") }
                FilledTonalIconButton(onClick = importMedia, enabled = !busy) { Icon(Icons.Rounded.Add, "Ajouter des médias") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
            if (page == Page.SOURCES) {
                Sources(library, importMedia, { streamDialog = true }, { freeMediaOpen = true }, { radiosOpen = true }, { youtubeOpen = true }, { downloadsOpen = true }, { cinemaOpen = true }, scan, { vm.restoreHidden() }, busy)
            } else if (page == Page.HOME) {
                EchoHome(library, prefs, vm, player, settings.reduceMotion,
                    {
                        (context as? MainActivity)?.externalVideo = true
                        context.startActivity(android.content.Intent(context, VideoActivity::class.java))
                    },
                    { link -> if (link != null) sharedLink?.value = link; youtubeOpen = true },
                    { id -> collectionId = id ?: "__new__"; collectionsOpen = true }, { play(it) }, scan)
            } else {
                if (page == Page.MUSIC) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tracks" to "Morceaux", "artists" to "Artistes", "albums" to "Albums").forEach { (key, label) ->
                        FilterChip(musicTab == key, { musicTab = key; folder = "" }, { Text(label) })
                    }
                }
                if (page == Page.VIDEO) VideoFilters(videoSection, videoCategory, library, { videoSection = it; videoCategory = "Toutes"; folder = "" }, { videoCategory = it })
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Retrouver un média…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Effacer la recherche") } })
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = favorites, onClick = { favorites = !favorites }, label = { Text("Mes favoris") },
                        leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(16.dp)) })
                    Spacer(Modifier.weight(1f))
                    Text("${visible.size} médias", color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, null); Text(sort) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf("Récents", "Nom A–Z", "Durée").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { sort = option; sortMenu = false }) }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { folderMenu = true }) { Text(folder.ifBlank { "Tous les dossiers" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        DropdownMenu(folderMenu, { folderMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            DropdownMenuItem(text = { Text("Tous les dossiers") }, onClick = { folder = ""; folderMenu = false })
                            (library.filter { page.kind == null || it.kind == page.kind }.map { it.folder }.filter { it.isNotBlank() } + library.map { it.artist }.filter { it.isNotBlank() }.map { "Artiste · $it" } + library.map { it.album }.filter { it.isNotBlank() }.map { "Album · $it" }).distinct().sorted().forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { folder = option; folderMenu = false })
                            }
                        }
                    }
                }
                if (library.isEmpty()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { Welcome(scan) }
                        item { Text("Un espace pour tout ce que tu aimes.", color = Muted) }
                        item { Feature(Icons.Rounded.Headphones, "Écoute sans interruption", "Garde ta musique pendant que tu parcours tes photos.") }
                        item { Feature(Icons.Rounded.FolderOpen, "Tes fichiers, simplement", "Ajoute tes morceaux, vidéos et photos depuis le téléphone.") }
                    }
                } else if (visible.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if(page == Page.VIDEO) "Aucune vidéo ici. Classe tes vidéos depuis leur menu ⋮ ou ajoute un lien dans Sources." else "Aucun média ici pour le moment.", color = Muted)
                    }
                } else if (page == Page.VIDEO) {
                    VideoLibrary(visible, { play(it) }, { organizeItem = it }, { vm.favorite(it) }, { vm.remove(it) })
                } else if (page == Page.MUSIC && musicTab != "tracks") {
                    MusicCatalog(visible, musicTab == "artists", { entry, queue -> play(entry, queue) }, { vm.favorite(it) })
                } else if (page == Page.PHOTO) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 150.dp)) {
                        items(visible, key = { it.uri }) { item ->
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Panel).clickable { play(item) }) {
                                AsyncImage(item.uri, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                IconButton(onClick = { vm.favorite(item) }, modifier = Modifier.align(Alignment.TopEnd)
                                    .background(Ink.copy(alpha = .7f), CircleShape)) {
                                    Icon(if (item.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        if (item.favorite) "Retirer des favoris" else "Ajouter aux favoris", tint = Lime)
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 150.dp)) {
                        if (page == Page.HOME && query.isBlank() && !favorites) {
                            val lastId = context.getSharedPreferences("playback", Context.MODE_PRIVATE).getString("last", null)
                            val last = library.find { it.uri == lastId }
                            if (last != null) item { ResumeCard(last) { play(last) } }
                            item { Text("Ta bibliothèque", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp)) }
                        }
                        if (chosen.isNotEmpty()) item {
                            SelectionBar(chosen.size,
                                onPlaylist = { playlistPicker = true },
                                onRemove = { vm.removeAll(library.filter { it.uri in chosen }); chosen = emptySet() },
                                onClear = { chosen = emptySet() })
                        }
                        items(visible, key = { it.uri }) { item ->
                            MediaRow(item, playback.id == item.uri, { play(item) }, { vm.favorite(item) }, { vm.remove(item) }, { tagItem = item }, { organizeItem = item }, { undoItem = item }, player,
                                picked = item.uri in chosen, picking = chosen.isNotEmpty(),
                                onPick = { chosen = if (item.uri in chosen) chosen - item.uri else chosen + item.uri })
                        }
                    }
                }
            }
        }
    }
    if (playback.id.isNotEmpty()) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = navigationHeight + 6.dp)) {
        MiniPlayer(playback, player, library.find { it.uri == playback.id }, { queueOpen = true }) { if (playback.video) { (context as? MainActivity)?.externalVideo = true; context.startActivity(android.content.Intent(context, VideoActivity::class.java)) } else playerOpen = true }
    }
    LiquidNavigation(if(page.ordinal >= 4) page.ordinal + 1 else page.ordinal, settings.reduceMotion, Modifier.align(Alignment.BottomCenter).onSizeChanged { size -> navigationHeight = with(density) { size.height.toDp() } }) { index ->
        if(index == 4) context.startActivity(android.content.Intent(context, NotesActivity::class.java))
        else { page = Page.entries[if(index > 4) index - 1 else index]; query = ""; favorites = false; folder = ""; chosen = emptySet() }
    }
    }
    undoItem?.let { entry -> MetadataUndoDialog(entry, { vm.undoMetadata(entry) }) { undoItem = null } }
    organizeItem?.let { entry -> VideoCategoryDialog(entry, library, { section, category -> vm.organizeVideo(entry, section, category) }) { organizeItem = null } }
    if (collectionsOpen) CollectionsScreen(library, player, { ids -> albumPhotos = ids; photoId = ids.firstOrNull() }, { collectionsOpen = false; collectionId = null }, collectionId)
    if (settingsOpen) SettingsScreen(settings, prefs, library, vm) { settingsOpen = false }
    if (streamDialog) StreamDialog({ streamDialog = false }) { name, url, kind -> vm.stream(name, url, kind); streamDialog = false }
    if (freeMediaOpen) FreeMediaScreen(vm) { freeMediaOpen = false }
    if (radiosOpen) RadioScreen(vm, player, library) { radiosOpen = false }
    if (downloadsOpen) DownloadsScreen(vm, prefs) { downloadsOpen = false }
    if (cinemaOpen) CinemaScreen(vm, prefs, { url, title ->
        player?.let { controller ->
            controller.setMediaItem(MediaItem.Builder().setMediaId(url).setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(if (android.net.Uri.parse(url).host == "archive.org") "Internet Archive" else "Echo-All Cinéma")
                    .setExtras(Bundle().apply { putBoolean("video", true) }).build()).build())
            controller.prepare(); controller.play()
            (context as? MainActivity)?.externalVideo = true
            context.startActivity(android.content.Intent(context, VideoActivity::class.java))
        } ?: run { vm.message.value = "Le lecteur se prépare…" }
    }) { cinemaOpen = false }
    if (queueOpen) player?.let { QueueDialog(it) { queueOpen = false } }
    if (playlistPicker) PlaylistPicker(library.filter { it.uri in chosen }.map { it.uri }) { playlistPicker = false; chosen = emptySet() }
    if (youtubeOpen) YouTubeScreen(vm, player, prefs, sharedLink?.value, { sharedLink?.value = null }, {
        (context as? MainActivity)?.externalVideo = true
        context.startActivity(android.content.Intent(context, VideoActivity::class.java))
    }) { youtubeOpen = false }
    tagItem?.let { target -> TagDialog(target, { match, text, cover -> vm.applyTags(target, match, text, cover) }) { tagItem = null } }
    if (playerOpen && player != null) PlayerScreen(player, playback, playback.video) {
        if (playback.video) player.pause()
        playerOpen = false
    }
    val photos = albumPhotos?.mapNotNull { uri -> library.find { it.uri == uri } } ?: visible.filter { it.kind == MediaKind.PHOTO }
    if (photoId != null) PhotoScreen(photos, photoId!!, settings.slideshowSeconds, { photoId = it }, { vm.favorite(it) }, { photoId = null; albumPhotos = null })
}

@Composable
private fun Welcome(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
        Brush.linearGradient(listOf(lerp(Ink, Lime, .28f), Panel))).padding(24.dp)) {
        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(48.dp), tint = Lime)
        Spacer(Modifier.height(28.dp))
        Text("Tout ton univers.\nUn seul endroit.", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
        Text("Musique, vidéos, photos.\nCommence avec les fichiers de ton téléphone.", color = Muted,
            modifier = Modifier.padding(top = 14.dp, bottom = 24.dp), lineHeight = 23.sp)
        Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Scanner mon téléphone")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(end = 14.dp, top = 3.dp), tint = Lime)
        Column { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ResumeCard(item: LibraryItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(lerp(Panel, Lime, .16f)).clickable(onClick = onClick).padding(22.dp)) {
        Text("ON REPREND ?", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp)
        Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayArrow, null, tint = Lime); Text("Reprendre la lecture", color = Lime) }
    }
}

private fun MediaKind.icon() = when (this) {
    MediaKind.MUSIC -> Icons.Rounded.MusicNote
    MediaKind.VIDEO -> Icons.Rounded.PlayCircle
    MediaKind.PHOTO -> Icons.Rounded.Photo
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaRow(item: LibraryItem, active: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onRemove: () -> Unit, onTag: () -> Unit, onOrganize: () -> Unit, onUndoMetadata: () -> Unit, player: MediaController? = null,
                     picked: Boolean = false, picking: Boolean = false, onPick: () -> Unit = {}) {
    val rowContext = LocalContext.current
    val chooseCover = rememberCoverPicker(item.uri)
    val customCover = rememberCover(item.uri)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(if (picked) lerp(Panel, Lime, .26f) else if (active) lerp(Panel, Lime, .13f) else Color.Transparent)
        .combinedClickable(onClick = { if (picking) onPick() else onPlay() }, onLongClick = onPick)
        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(lerp(Panel, Lime, .18f)), contentAlignment = Alignment.Center) {
            if (picking) Icon(if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (picked) "Sélectionné" else "Non sélectionné", tint = Lime)
            else MediaThumbnail(item, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOf(item.artist.ifBlank { item.source }, if (item.durationMs > 0) time(item.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options pour ${item.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if(item.kind == MediaKind.VIDEO) DropdownMenuItem(text = { Text("Classer la vidéo") }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = { menu = false; onOrganize() })
                if(item.kind != MediaKind.PHOTO) DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = {
                    player?.let { it.addMediaItem((it.currentMediaItemIndex + 1).coerceIn(0,it.mediaItemCount), item.playable()) }; menu = false
                })
                if (item.kind == MediaKind.MUSIC) DropdownMenuItem(text = { Text("Trouver pochette et infos") }, leadingIcon = { Icon(Icons.Rounded.TravelExplore, null) }, onClick = { onTag(); menu = false })
                if (item.metadataUndo.isNotBlank()) DropdownMenuItem(text = { Text("Annuler la correction automatique") }, onClick = { menu = false; onUndoMetadata() })
                if (item.kind == MediaKind.MUSIC) DropdownMenuItem(text = { Text("Choisir une pochette") }, onClick = { chooseCover(); menu = false })
                if (customCover != null) DropdownMenuItem(text = { Text("Retirer la pochette personnalisée") }, onClick = { clearCover(rowContext, item.uri); menu = false })
                DropdownMenuItem(text = { Text("Partager le fichier ou le lien") }, onClick = { shareMedia(rowContext,item); menu = false })
                DropdownMenuItem(text = { Text(if (item.favorite) "Retirer des favoris" else "Ajouter aux favoris") },
                    onClick = { onFavorite(); menu = false })
                DropdownMenuItem(text = { Text("Retirer de la bibliothèque") }, onClick = { onRemove(); menu = false })
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlaybackState, player: Player?, item: LibraryItem?, onQueue: () -> Unit, onExpand: () -> Unit) {
    val (motion) = rememberPreferences()
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(22.dp)).background(lerp(Panel, Lime, .07f)).clickable(onClick = onExpand)) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Custom cover, else the picture embedded in the file, else an icon (also for radios and links not in the library).
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(lerp(Panel, Lime, .18f)), contentAlignment = Alignment.Center) {
                MediaThumbnail(item ?: LibraryItem(state.id, state.title, if (state.video) MediaKind.VIDEO else MediaKind.MUSIC), Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Crossfade(state.title, animationSpec = tween(if(motion.reduceMotion) 0 else 220), label = "miniTitle") { title -> Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) }
                Text(if (state.buffering) "Chargement…" else if (state.playing) "En lecture" else "En pause", color = Muted, fontSize = 11.sp)
            }
            PlaybackPulse(state.playing, motion.reduceMotion, Modifier.size(width = 22.dp, height = 20.dp).padding(end = 4.dp))
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "File de lecture", tint = Muted) }
            IconButton(onClick = { toggle(player) }) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) "Mettre en pause" else "Lire", tint = Lime) }
        }
        if (state.duration > 0) LinearProgressIndicator(progress = { (state.position.toFloat() / state.duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = Panel)
    }
}

private fun toggle(player: Player?) {
    if (player == null) return
    if (player.isPlaying) player.pause() else {
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
    }
}

@Composable
private fun Sources(library: List<LibraryItem>, onImport: () -> Unit, onStream: () -> Unit, onFreeMedia: () -> Unit, onRadios: () -> Unit, onYouTube: () -> Unit, onDownloads: () -> Unit, onCinema: () -> Unit, onScan: () -> Unit, onRestore: () -> Unit, busy: Boolean) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 170.dp)) {
        item { Text("Tes médias, où qu’ils soient.", color = Muted) }
        item { SourceCard(Icons.Rounded.PhoneAndroid, "Ce téléphone", "Détecte tes musiques, vidéos et photos accessibles, sans les choisir une par une.", if (busy) "Scan en cours…" else "Scanner / gérer les accès", { if (!busy) onScan() }) }
        item { TextButton(onClick = onImport) { Text("Ajouter des fichiers manuellement") }
            TextButton(onClick = onRestore, enabled = !busy) { Text("Réafficher les médias masqués du scan") }
            Text("Le scan respecte les autorisations Android. Sur Android 14 et plus, tu peux autoriser toutes les photos ou seulement une sélection.", color = Muted, fontSize = 12.sp)
        }
        item { SourceCard(Icons.Rounded.Theaters, "Films", "Une vitrine par genre, une fiche avec le synopsis et la licence, et la lecture directe sans attendre le téléchargement.", "Ouvrir", onCinema) }
        item { SourceCard(Icons.Rounded.SmartDisplay, "YouTube", "L’espace complet : playlists, chaînes, vidéos, qualité et format des téléchargements. La recherche simple est déjà sur l’accueil.", "Ouvrir", onYouTube) }
        item { SourceCard(Icons.Rounded.Radio, "Radios", "Des milliers de radios du monde entier : écoute en direct et garde tes préférées dans ta bibliothèque.", "Explorer", onRadios) }
        item { SourceCard(Icons.Rounded.CloudDownload, "Musique et vidéos libres", "Cherche une musique ou une vidéo libre de droits (Internet Archive) et télécharge-la avec son titre, son artiste et sa pochette.", "Rechercher", onFreeMedia) }
        item { SourceCard(Icons.Rounded.Download, "Téléchargements", "Ce qui est en cours, ce qui a échoué et peut être relancé d’un geste, et la place que tes fichiers téléchargés occupent.", "Ouvrir", onDownloads) }
        item { SourceCard(Icons.Rounded.Language, "Streaming", "Ajoute un lien direct HTTPS audio ou vidéo : MP3, MP4, HLS ou DASH selon le flux.", "Ajouter un lien", onStream) }
        item { Text("Un lien Spotify, Netflix ou Deezer n’est pas un flux direct et n’est pas pris en charge. Les liens YouTube passent par l’espace YouTube ci-dessus.", fontSize = 12.sp, color = Muted) }
    }
}

@Composable
private fun SelectionBar(count: Int, onPlaylist: () -> Unit, onRemove: () -> Unit, onClear: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = lerp(Panel, Lime, .18f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 1) "1 sélectionné" else "$count sélectionnés", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onPlaylist) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à une playlist", tint = Lime) }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Retirer de la bibliothèque", tint = Lime) }
            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Tout désélectionner") }
        }
    }
}

/** Sends a selection into an existing playlist, or into a new one. */
@Composable
private fun PlaylistPicker(uris: List<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    val lists = remember { store.lists() }
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDone, title = { Text("Ajouter à une playlist") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nouvelle playlist") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank(), onClick = { store.saveList(store.newList(name, uris)); onDone() }) { Text("Créer") }
                }
            }
            items(lists, key = { it.id }) { list ->
                TextButton(onClick = {
                    // Keep the existing order and drop anything already there, so nothing is listed twice.
                    store.saveList(list.copy(uris = (list.uris + uris).distinct())); onDone()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(list.name.ifBlank { "Sans titre" } + " · " + list.uris.size, modifier = Modifier.weight(1f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDone) { Text("Fermer") } })
}

@Composable
private fun SourceCard(icon: ImageVector, title: String, description: String, action: String, onClick: () -> Unit) {
    val reduced = rememberPreferences().first.reduceMotion
    val breath = rememberBreath(reduced)
    Surface(shape = RoundedCornerShape(24.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Lime.copy(alpha = .06f + .09f * breath)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Lime) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Text(action, modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StreamDialog(onDismiss: () -> Unit, onSave: (String, String, MediaKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(MediaKind.MUSIC) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Un nouveau flux") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nom du flux") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("Lien HTTPS direct") }, singleLine = true,
                isError = url.isNotEmpty() && !validStreamUrl(url))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(kind == MediaKind.MUSIC, { kind = MediaKind.MUSIC }, { Text("Audio") })
                FilterChip(kind == MediaKind.VIDEO, { kind = MediaKind.VIDEO }, { Text("Vidéo") })
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, url, kind) }, enabled = validStreamUrl(url)) { Text("Ajouter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

private fun time(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(player: Player, state: PlaybackState, video: Boolean, onDismiss: () -> Unit) {
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    if (lyricsOpen) LyricsScreen(player) { lyricsOpen = false }
    val videoContext = LocalContext.current
    val customCover = rememberCover(state.id)
    val chooseCover = rememberCoverPicker(state.id)
    val (settings) = rememberPreferences()
    val coverScale by animateFloatAsState(if(state.playing) 1f else .96f, if(settings.reduceMotion) snap() else spring(dampingRatio = .8f, stiffness = 180f), label = "coverScale")
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(state.id) {
        if (video && settings.mixSeconds > 0 && !settings.reduceMotion) { opacity.snapTo(0f); opacity.animateTo(1f, tween(450)) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.KeyboardArrowDown, "Fermer le lecteur") }
                        Text("EN LECTURE", Modifier.weight(1f), color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                        if (!video) IconButton(onClick = chooseCover) { Icon(Icons.Rounded.Image, "Changer la pochette") }
                    }
                }
                item {
                    if (video) Box(Modifier.fillMaxWidth().aspectRatio(16f/9f), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayCircle, "Ouvrir le plein écran", Modifier.size(72.dp), tint = Lime) }
                    else Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = coverScale; scaleY = coverScale }.clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(lerp(Panel, Lime, .42f), Panel, lerp(Ink, Lime, .2f)))),
                        contentAlignment = Alignment.Center) {
                        if (customCover != null) AsyncImage(customCover, "Pochette", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else MediaThumbnail(LibraryItem(state.id, state.title, MediaKind.MUSIC), Modifier.fillMaxSize())
                    }
                }

                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.title, style = MaterialTheme.typography.headlineLarge)
                    player.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyLarge) }
                } }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(value = dragging ?: state.position.toFloat().coerceAtMost(state.duration.toFloat()),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()) }; dragging = null },
                            valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.duration > 0 && player.isCurrentMediaItemSeekable)
                        Row { Text(time(state.position), color = Muted); Spacer(Modifier.weight(1f)); Text(if (state.duration > 0) time(state.duration) else "En direct", color = Muted) }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = player.hasPreviousMediaItem()) { Icon(Icons.Rounded.SkipPrevious, "Média précédent") }
                        FilledIconButton(onClick = { toggle(player) }, modifier = Modifier.size(76.dp)) {
                            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (state.playing) "Mettre en pause" else "Lire", Modifier.size(36.dp))
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }, enabled = player.hasNextMediaItem()) { Icon(Icons.Rounded.SkipNext, "Média suivant") }
                    }
                }
                if (video) item { FilledTonalButton(onClick = { (videoContext as? MainActivity)?.externalVideo = true; videoContext.startActivity(android.content.Intent(videoContext, VideoActivity::class.java)) }) { Text("Plein écran et fenêtre flottante") } }
                if (!video) item { LyricsPreview(player, state.id) { lyricsOpen = true } }
                item { PlaybackExtras(player, state.shuffle, state.repeat, state.speed) }
                if (state.buffering) item { Text("Chargement du média…", color = Lime) }
                if (player.playerError != null) item { Text("Ce média ne peut pas être lu. Vérifie son accès, puis appuie sur Lecture pour réessayer.", color = MaterialTheme.colorScheme.error) }
                item { Text(if (video) "Fermer le lecteur met la vidéo en pause." else "La musique te suit, même écran verrouillé.", color = Muted, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun PhotoScreen(photos: List<LibraryItem>, id: String, slideshowSeconds: Int, onSelect: (String) -> Unit, onFavorite: (LibraryItem) -> Unit, onDismiss: () -> Unit) {
    val photoContext = LocalContext.current
    var photoMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var rotation by remember(id) { mutableFloatStateOf(0f) }
    val index = photos.indexOfFirst { it.uri == id }
    val photo = photos.getOrNull(index) ?: return
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    var slideshow by rememberSaveable { mutableStateOf(false) }
    val photoOwner = LocalLifecycleOwner.current
    DisposableEffect(photoOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) slideshow = false }
        photoOwner.lifecycle.addObserver(observer)
        onDispose { photoOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(slideshow, id, slideshowSeconds) {
        if (slideshow && photos.size > 1) { delay(slideshowSeconds * 1000L); onSelect(photos[(index + 1) % photos.size].uri) }
    }
    var failed by remember(id) { mutableStateOf(false) }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(photo.title) }, text = { Text("${photo.folder.ifBlank { photo.source }}\n${photoContext.contentResolver.getType(android.net.Uri.parse(photo.uri)).orEmpty()}\n${photo.uri}") }, confirmButton = { TextButton(onClick = { details = false }) { Text("Fermer") } })
    val transform = rememberTransformableState { zoom, pan, _ ->
        slideshow = false
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer la photo") }
                    Text(photo.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { slideshow = !slideshow }, enabled = photos.size > 1) { Icon(if (slideshow) Icons.Rounded.Pause else Icons.Rounded.Slideshow, if (slideshow) "Arrêter le diaporama" else "Lancer le diaporama") }
                    Box {
                        IconButton(onClick = { photoMenu = true }) { Icon(Icons.Rounded.MoreVert, "Options de la photo") }
                        DropdownMenu(photoMenu, { photoMenu = false }) {
                            DropdownMenuItem(text = { Text("Partager") }, onClick = { shareMedia(photoContext, photo); photoMenu = false })
                            DropdownMenuItem(text = { Text("Faire pivoter l’affichage") }, onClick = { rotation = (rotation + 90f) % 360; photoMenu = false })
                            DropdownMenuItem(text = { Text("Informations") }, onClick = { details = true; photoMenu = false })
                        }
                    }
                    IconButton(onClick = { onFavorite(photo) }) { Icon(if (photo.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (photo.favorite) "Retirer des favoris" else "Ajouter aux favoris", tint = Lime) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))
                    .pointerInput(id) { detectTapGestures(onDoubleTap = { slideshow = false; scale = if(scale>1f)1f else 2f; offset = Offset.Zero }) }
                    .pointerInput(id, scale) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                            if(scale == 1f && kotlin.math.abs(drag)>80f) { slideshow=false; photos.getOrNull(index + if(drag<0)1 else -1)?.let { onSelect(it.uri) } }
                        }, onHorizontalDrag = { _, amount -> drag += amount })
                    }.transformable(transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
                    AsyncImage(photo.uri, photo.title, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; rotationZ = rotation
                    }, contentScale = ContentScale.Fit, onError = { failed = true })
                    if (failed) Text("Photo inaccessible. Ajoute-la à nouveau depuis le téléphone.", Modifier.padding(24.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { slideshow = false; onSelect(photos[index - 1].uri) }, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, "Photo précédente") }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("${index + 1} / ${photos.size} · Recentrer") }
                    IconButton(onClick = { slideshow = false; onSelect(photos[index + 1].uri) }, enabled = index < photos.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Photo suivante") }
                }
            }
        }
    }
}
