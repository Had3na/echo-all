package fr.nacre.media

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DjScreen(player: MediaController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    val (settings, prefs) = rememberPreferences()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(Bundle()) }
    var tools by remember { mutableStateOf(TrackTools()) }
    var id by remember { mutableStateOf(player.currentMediaItem?.mediaId.orEmpty()) }
    var position by remember { mutableLongStateOf(0) }
    var bpmText by remember(id) { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(emptyList<Long>()) }
    var party by remember { mutableStateOf(false) }
    var sync by remember { mutableStateOf(prefs.getBoolean("tempoSync", false)) }
    var style by remember { mutableStateOf(prefs.getString("mixStyle", "smooth").orEmpty()) }
    fun command(op: String, value: Float = 0f) {
        val future = player.sendCustomCommand(SessionCommand("studio", Bundle.EMPTY), Bundle().apply { putString("op", op); putFloat("value", value) })
        future.addListener({ runCatching { status = future.get().extras } }, ContextCompat.getMainExecutor(context))
    }
    fun save(value: TrackTools) { store.saveTrack(id, value); tools = value }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(player) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { id = player.currentMediaItem?.mediaId.orEmpty(); position = player.currentPosition; tools = store.track(id); command("status"); delay(250) }
    } }
    DisposableEffect(player) { onDispose { player.sendCustomCommand(SessionCommand("studio", Bundle.EMPTY), Bundle().apply { putString("op", "releaseUi") }) } }
    val view = LocalView.current
    DisposableEffect(party, view) { val previous = view.keepScreenOn; if (party) view.keepScreenOn = true; onDispose { view.keepScreenOn = previous } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("STUDIO DJ", fontSize = 28.sp, color = Lime, modifier = Modifier.weight(1f)); IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer le studio") } } }
                item { Text(player.mediaMetadata.title?.toString().orEmpty(), fontSize = 20.sp) }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Mode soirée · garder l’écran allumé", Modifier.weight(1f)); Switch(party, { party = it }) } }
                item {
                    if (tools.wave.isNotEmpty()) {
                        val color = Lime
                        Canvas(Modifier.fillMaxWidth().height(80.dp).background(Panel)) { tools.wave.forEachIndexed { i, amplitude ->
                            val x = size.width * i / tools.wave.size
                            drawLine(color, Offset(x, size.height / 2 - amplitude * size.height / 2), Offset(x, size.height / 2 + amplitude * size.height / 2), strokeWidth = (size.width / tools.wave.size - 1).coerceAtLeast(1f))
                        } }
                    }
                    Text("Enveloppe audio des 90 premières secondes analysées.", color = Muted, fontSize = 11.sp)
                }
                item {
                    Text("4 pads de repérage", fontSize = 20.sp)
                    Text("Mémorise la position puis rappelle-la pendant le mix. Les repères restent liés à ce morceau.", color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(4) { pad ->
                            var saved by remember(id, pad) { mutableLongStateOf(store.pad(id, pad)) }
                            Column(Modifier.weight(1f)) {
                                Button(onClick = { if (saved >= 0) player.seekTo(saved) }, enabled = saved >= 0 && id.isNotBlank(), contentPadding = PaddingValues(4.dp), modifier = Modifier.fillMaxWidth()) { Text("CUE "+(pad+1)) }
                                Text(if (saved < 0) "Vide" else "%d:%02d".format(saved/60000, saved/1000%60), fontSize = 12.sp)
                                TextButton(onClick = { saved = position.coerceAtLeast(0); store.setPad(id, pad, saved) }, enabled = id.isNotBlank(), contentPadding = PaddingValues(0.dp)) { Text("Poser", fontSize = 12.sp) }
                                if (saved >= 0) TextButton(onClick = { saved = -1; store.clearPad(id, pad) }, contentPadding = PaddingValues(0.dp)) { Text("Effacer", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                item { Text("Tempo et préparation", fontSize = 20.sp) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !loading && id.startsWith("content:"), onClick = {
                        loading = true; val targetId = id
                        scope.launch {
                            try { val result = analyzeAudio(context, targetId); val old = store.track(targetId)
                                store.saveTrack(targetId, old.copy(bpm = result.bpm, confidence = result.confidence, wave = result.wave))
                                message = if (result.bpm > 0) "Tempo estimé : %.1f BPM. Corrige si nécessaire.".format(result.bpm) else "Tempo incertain : utilise TAP ou une valeur manuelle."
                            } catch (error: Exception) { message = error.message ?: "Analyse impossible." }
                            finally { loading = false }
                        }
                    }) { Text(if (loading) "Analyse…" else "Analyser le BPM") }
                    OutlinedButton(onClick = {
                        val now = SystemClock.elapsedRealtime()
                        taps = if (taps.lastOrNull()?.let { now - it > 2000 } != false) listOf(now) else (taps + now).takeLast(8)
                        val bpm = tappedBpm(taps)
                        if (bpm > 0) { save(tools.copy(bpm = bpm, confidence = 1f)); message = "Tempo TAP enregistré." }
                    }) { Text("TAP · ${taps.size}") }
                } }
                item { Text(if (tools.bpm > 0) "%.1f BPM · estimation/correction enregistrée".format(tools.bpm) else "BPM non renseigné", color = Lime) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(bpmText, { bpmText = it }, label = { Text("BPM manuel · 40 à 240") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = bpmText.toFloatOrNull()?.let { it in 40f..240f } == true, onClick = { save(tools.copy(bpm = bpmText.toFloat(), confidence = 1f)); bpmText = "" }) { Text("OK") }
                } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("SYNC tempo"); Text("Ajuste la vitesse du prochain titre, dans ±25 %. Ne cale pas la phase des battements.", color = Muted, fontSize = 12.sp) }; Switch(sync, { sync = it; prefs.edit().putBoolean("tempoSync", it).apply() }) } }
                item { Text("Points de passage · ${formatPosition(position)}", fontSize = 18.sp) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { save(tools.copy(cueIn = position, cueOut = tools.cueOut.takeIf { it > position } ?: 0)) }) { Text("Marquer entrée") }
                    OutlinedButton(onClick = { if (position > tools.cueIn) save(tools.copy(cueOut = position)) else message = "La sortie doit suivre l’entrée." }) { Text("Marquer sortie") }
                    Button(onClick = { command("cue") }) { Text("CUE") }
                } }
                item { Text("Entrée ${formatPosition(tools.cueIn)} · Sortie ${if (tools.cueOut > 0) formatPosition(tools.cueOut) else "fin du fichier"}", color = Muted)
                    TextButton(onClick = { save(tools.copy(cueIn = 0, cueOut = 0, loop = false)) }) { Text("Réinitialiser les points") } }
                item { Text("Boucle rythmique", fontSize = 18.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4,8,16).forEach { beats -> OutlinedButton(enabled = tools.bpm > 0, onClick = {
                            val end = position + (beats * 60_000f / tools.bpm).toLong()
                            if (player.duration <= 0 || end <= player.duration) save(tools.copy(loopIn = position, loopOut = end, loop = true))
                            else message = "Pas assez de durée restante pour cette boucle."
                        }) { Text("$beats temps") } }
                        TextButton(onClick = { save(tools.copy(loop = false)) }) { Text("Arrêter") }
                    }
                    if (tools.loop) Text("Boucle active · ${formatPosition(tools.loopIn)} → ${formatPosition(tools.loopOut)}", color = Lime)
                }
                item { HorizontalDivider(); Text("Transition", fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("smooth" to "Douce", "club" to "Club · basses", "sweep" to "Filtre", "linear" to "Linéaire", "cut" to "Coupure").forEach { (key, title) -> FilterChip(style == key, { style = key; prefs.edit().putString("mixStyle", key).apply() }, { Text(title) }) }
                }
                    Text("Club : échange des basses. Filtre : A s’amincit pendant que B s’ouvre. Traitement intégré, identique sur tous les téléphones.", color = Muted, fontSize = 12.sp)
                    Text("Durée · ${settings.mixSeconds} s")
                    Slider(settings.mixSeconds.toFloat(), { prefs.edit().putInt("mixSeconds", it.toInt()).apply() }, valueRange = 0f..60f, steps = 59)
                }
                item { Text("Régler la durée depuis le tempo", fontSize = 16.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(8, 16, 32, 64).forEach { beats -> OutlinedButton(enabled = tools.bpm > 0, onClick = {
                            val seconds = Math.round(beats * 60f / (tools.bpm * player.playbackParameters.speed)).coerceIn(1, 60)
                            prefs.edit().putInt("mixSeconds", seconds).apply()
                            message = "Durée réglée sur " + beats + " temps, arrondie à " + seconds + " s."
                        }) { Text(beats.toString()+" temps") } }
                    }
                    Text("Utilise le BPM du morceau actif ; ce réglage ne cale pas la phase des battements.", color = Muted, fontSize = 11.sp)
                }
                item { Text("A · ${status.getString("a", "")}"); Text("B · ${status.getString("b", "Prochain média")}", color = Lime) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { command("arm") }, enabled = !status.getBoolean("mixing") && settings.mixSeconds > 0) { Text("Préparer B") }
                    Button(onClick = { command("manual") }, enabled = status.getBoolean("ready")) { Text("Lancer A + B") }
                    OutlinedButton(onClick = { command("finish") }) { Text("Terminer vers B") }
                } }
                item { Text("Mixage manuel A ↔ B", fontSize = if (party) 24.sp else 18.sp)
                    Slider(status.getFloat("blend"), { value -> status = Bundle(status).apply { putFloat("blend", value) }; command("blend", value) }, enabled = status.getBoolean("manual"), modifier = Modifier.fillMaxWidth().height(if (party) 80.dp else 48.dp))
                    Text(status.getString("message", "").orEmpty(), color = Muted)
                }
                if (message.isNotBlank()) item { Text(message, color = Lime) }
                item { Text("Les boucles et fondus dépendent du décodage du téléphone ; pas de scratch ni de grille de battements précise dans cette version.", color = Muted, fontSize = 12.sp) }
            }
        }
    }
}

fun formatPosition(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1000) % 60)
