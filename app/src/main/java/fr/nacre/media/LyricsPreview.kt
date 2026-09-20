package fr.nacre.media

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LyricsPreview(player: Player, id: String, onExpand: () -> Unit) {
    val context = LocalContext.current
    val (settings, prefs) = rememberPreferences()
    val owner = LocalLifecycleOwner.current
    var revision by remember(id) { mutableIntStateOf(0) }
    var data by remember(id) { mutableStateOf<JSONObject?>(null) }
    var position by remember(id) { mutableLongStateOf(player.currentPosition) }
    var offset by remember(id) { mutableIntStateOf(prefs.getInt("lyricsOffset:$id", 0)) }
    DisposableEffect(id) {
        val stop = StudioEvents.listen { if(it == "companion:lyrics:$id") revision++ }
        onDispose { stop() }
    }
    LaunchedEffect(id, revision) {
        data = withContext(Dispatchers.IO) { StudioStore(context).cached("lyrics:$id")?.let { runCatching { JSONObject(it) }.getOrNull() } }
    }
    LaunchedEffect(player, id, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { position = player.currentPosition; offset = prefs.getInt("lyricsOffset:$id", 0); delay(150) }
        }
    }
    val lines = remember(data) { parseLrc(data?.optString("lrc").orEmpty()) }
    val active = activeLyric(lines, position + offset)
    Surface(onClick = onExpand, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Panel) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PAROLES", color = Lime, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.OpenInFull, "Ouvrir les paroles en plein écran", Modifier.size(18.dp), tint = Muted)
            }
            if(lines.isNotEmpty()) {
                val start = (active - 1).coerceAtLeast(0)
                for(index in start until (start + 3).coerceAtMost(lines.size)) {
                    val color by animateColorAsState(if(index == active) Lime else Muted, if(settings.reduceMotion) snap() else tween(180), label = "previewLyric")
                    Text(lines[index].text.ifBlank { "Interlude" }, fontSize = if(index == active) 21.sp else 17.sp, lineHeight = 28.sp, color = color,
                        fontWeight = if(index == active) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = player.isCurrentMediaItemSeekable) { player.seekTo((lines[index].timeMs-offset).coerceAtLeast(0)) })
                }
                Text("${data?.optString("source").orEmpty()} · Toucher une ligne pour s’y déplacer", color = Muted, fontSize = 10.sp)
            } else {
                Text(when {
                    data?.optBoolean("instrumental") == true -> "Morceau instrumental"
                    data?.optString("plain").orEmpty().isNotBlank() -> data!!.optString("plain").lineSequence().take(3).joinToString("\n")
                    data != null -> "Paroles indisponibles pour cette version. Tu peux importer un fichier .lrc."
                    prefs.getBoolean("private", false) || !prefs.getBoolean("autoLyrics", true) -> "Recherche automatique désactivée. Ouvre les paroles pour importer un .lrc."
                    else -> "Les paroles apparaîtront ici lorsqu’elles seront trouvées."
                }, color = Muted, maxLines = 5, overflow = TextOverflow.Ellipsis)
                if(data?.optString("plain").orEmpty().isNotBlank()) Text("Version non synchronisée", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun MetadataUndoDialog(item: LibraryItem, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val original = remember(item) { runCatching { undoAutomaticTags(item) }.getOrNull() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Annuler la correction automatique") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Actuellement", color = Muted)
            Text(listOf(item.title, item.artist, item.album).filter { it.isNotBlank() }.joinToString("\n"))
            Text("Après restauration", color = Muted)
            Text(original?.let { listOf(it.title, it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString("\n") } ?: "Informations d’origine indisponibles.")
            Text("La recherche automatique sera désactivée pour ce morceau. Tes favoris et tes playlists sont conservés.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }, confirmButton = { TextButton(enabled = original != null, onClick = { onUndo(); onDismiss() }) { Text("Restaurer l’original") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Garder") } })
}
