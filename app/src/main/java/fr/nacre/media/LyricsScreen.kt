package fr.nacre.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun LyricsScreen(player: Player, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (settings, prefs) = rememberPreferences()
    var id by remember { mutableStateOf(player.currentMediaItem?.mediaId.orEmpty()) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var revision by remember { mutableIntStateOf(0) }
    var data by remember(id) { mutableStateOf<JSONObject?>(null) }
    var status by remember(id) { mutableStateOf("") }
    var message by remember(id) { mutableStateOf("") }
    var follow by rememberSaveable(id) { mutableStateOf(true) }
    var offset by remember(id) { mutableIntStateOf(prefs.getInt("lyricsOffset:$id", 0)) }
    var requestedId by rememberSaveable { mutableStateOf(id) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(player, lifecycle) {
        while(lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
            id = player.currentMediaItem?.mediaId.orEmpty(); position = player.currentPosition; playing = player.isPlaying; delay(100)
        }
    }
    DisposableEffect(id) {
        val stop = StudioEvents.listen { key ->
            if(key == "companion:lyrics:$id" || key == "companion:status:$id") revision++
            if(key == "companion:error:$id") message = "Recherche indisponible. Tu peux importer un fichier LRC."
        }
        onDispose { stop() }
    }
    LaunchedEffect(id, revision) {
        val snapshot = withContext(Dispatchers.IO) { val store = StudioStore(context); store.cached("lyrics:$id") to store.cached("status:$id") }
        data = snapshot.first?.let { runCatching { JSONObject(it) }.getOrNull() }; status = snapshot.second.orEmpty()
    }
    val lines = remember(data) { parseLrc(data?.optString("lrc").orEmpty()) }
    val current = activeLyric(lines, position + offset)
    val list = rememberLazyListState()
    LaunchedEffect(current, follow, lines) {
        if(follow && current >= 0) {
            if(settings.reduceMotion) list.scrollToItem((current-1).coerceAtLeast(0)) else list.animateScrollToItem((current-1).coerceAtLeast(0))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            val target = requestedId
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = ByteArray(512_001); var used = 0
                            while(used < bytes.size) { val n = input.read(bytes, used, bytes.size-used); if(n < 0) break; used += n }
                            require(used <= 512_000) { "Fichier trop volumineux." }; String(bytes, 0, used, Charsets.UTF_8)
                        } ?: error("Fichier inaccessible.")
                        require(parseLrc(text).isNotEmpty()) { "Ce fichier ne contient pas de paroles horodatées LRC." }
                        StudioStore(context).cache("lyrics:$target", JSONObject().put("lrc", text).put("source", "Fichier LRC").put("checked", System.currentTimeMillis()).toString())
                    }
                    message = "Paroles importées."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "Import impossible." }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp)) {
                Row {
                    Column(Modifier.weight(1f).padding(top = 12.dp)) {
                        Text("PAROLES", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp)
                        Text(player.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer les paroles") }
                }
                if(message.isNotBlank()) Text(message, color = Muted)
                if(lines.isNotEmpty()) {
                    Row {
                        FilterChip(follow, { follow = !follow }, { Text("Suivre la voix") })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { if(player.isPlaying) player.pause() else player.play() }) { Text(if(playing) "Pause" else "Lecture") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 28.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        itemsIndexed(lines) { index, line ->
                            val color by animateColorAsState(if(index == current) Lime else Muted, if(settings.reduceMotion) snap() else tween(200), label = "lyricHighlight")
                            Text(line.text.ifBlank { "♪" }, color = color, fontSize = 26.sp, lineHeight = 36.sp, fontWeight = if(index == current) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = player.isCurrentMediaItemSeekable) { player.seekTo((line.timeMs-offset).coerceAtLeast(0)); follow = true })
                        }
                    }
                    Text("Décalage : ${offset / 1000f} s", color = Muted, fontSize = 12.sp)
                    Slider(offset.toFloat(), { offset = it.toInt() }, onValueChangeFinished = { prefs.edit().putInt("lyricsOffset:$id", offset).apply() }, valueRange = -5000f..5000f, steps = 39)
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 24.dp)) {
                        item {
                            Text(when {
                                data?.optBoolean("instrumental") == true -> "Morceau instrumental."
                                data?.optString("plain").orEmpty().isNotBlank() -> "Paroles disponibles sans synchronisation."
                                data != null -> "Aucune parole synchronisée trouvée pour cette version."
                                prefs.getBoolean("private", false) -> "Recherche en ligne désactivée en mode privé. Tu peux importer un fichier LRC."
                                !prefs.getBoolean("autoLyrics", true) -> "Active la recherche des paroles dans Réglages → Pendant l’écoute, ou importe un fichier LRC."
                                status.isNotBlank() -> status
                                else -> "La recherche accompagne la lecture. Si le titre ou l’artiste sont inconnus, renseigne-les avec « Trouver pochette et infos »."
                            }, color = Muted)
                            if(data?.optString("plain").orEmpty().isNotBlank()) Text(data!!.optString("plain"), fontSize = 22.sp, lineHeight = 32.sp, modifier = Modifier.padding(top = 24.dp))
                        }
                    }
                }
                Row {
                    TextButton(onClick = {
                        val target = id
                        scope.launch(Dispatchers.IO) {
                            val store = StudioStore(context)
                            val cached = store.cached("lyrics:$target")?.let(::JSONObject)
                            if(cached != null && cached.optString("source") != "Fichier LRC") store.cache("lyrics:$target", cached.put("checked", 0).put("signature", "").toString())
                            store.cache("attempt:$target", "0"); store.cache("retry:$target", System.currentTimeMillis().toString())
                        }
                        message = "Recherche relancée pendant la lecture."
                    }) { Text("Relancer") }
                    TextButton(onClick = { requestedId = id; importer.launch(arrayOf("*/*")) }) { Text("Importer un .lrc") }
                    Spacer(Modifier.weight(1f))
                    Text(data?.optString("source").orEmpty(), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}
