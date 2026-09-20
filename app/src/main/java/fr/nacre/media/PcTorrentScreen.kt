@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun PcTorrentScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf(runCatching { PcTorrentClient.load(context) }.getOrNull()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var playing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var files by remember { mutableStateOf<Pair<String, List<JSONObject>>?>(null) }
    fun work(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Connexion au PC impossible" }
            finally { busy = false }
        }
    }
    val pairing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(20000).toString(Charsets.UTF_8) }
            }
            PcTorrentClient.from(JSONObject(text)).call("/health")
            client = PcTorrentClient.save(context, text)
            message = "PC connecté. Les téléchargements restent sur cet ordinateur."
        }
    }
    val torrent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(4000000) } }
            client!!.call("/import", JSONObject().put("torrent", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            message = "Torrent envoyé au PC."
        }
    }
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        while (isActive) {
            try { jobs = c.call("/jobs").optJSONArray("jobs").cinemaObjects() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "PC injoignable. Vérifie qu’il est allumé et que le téléphone est sur le même réseau." }
            delay(3000)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Row {
                        Text("Mon serveur PC", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = onDismiss) { Text("Fermer") }
                    }
                }
                item {
                    Text(if (client == null) "Associe cet appareil avec le fichier Echo-All-Serveur.json créé sur le PC." else "Recherche et téléchargement sur le PC. Lecture des vidéos terminées par Wi-Fi.", color = Muted)
                    TextButton(enabled = !busy, onClick = { pairing.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                        Text(if (client == null) "Associer le PC" else "Changer de PC")
                    }
                }
                if (message.isNotBlank()) item { Text(message, color = Lime) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (client != null) {
                    item {
                        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Film, série ou anime") }, singleLine = true)
                        Button(enabled = !busy && query.trim().length >= 2, onClick = {
                            work {
                                val r = client!!.call("/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"))
                                hits = r.optJSONArray("hits").cinemaObjects()
                                message = r.optJSONArray("errors")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
                                if (hits.isEmpty()) message = "Aucun résultat. $message"
                            }
                        }) { Text("Rechercher sur le PC") }
                        TextButton(enabled = !busy, onClick = { torrent.launch(arrayOf("*/*")) }) { Text("Importer un fichier .torrent") }
                    }
                    items(hits.take(30), key = { "hit:" + it.getString("id") }) { hit ->
                        Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(hit.optString("title"))
                                Text(hit.optString("source"), color = Muted)
                                TextButton(enabled = !busy, onClick = { work {
                                    client!!.call("/download", JSONObject().put("id", hit.getString("id")))
                                    message = "Ajouté aux téléchargements du PC."
                                } }) { Text("Télécharger sur le PC") }
                            }
                        }
                    }
                    item { Text("Téléchargements du PC", style = MaterialTheme.typography.titleLarge) }
                    items(jobs, key = { it.getString("hash") }) { job ->
                        val hash = job.getString("hash")
                        val done = job.optDouble("progress") >= 1
                        val paused = job.optString("state").contains("stopped", true) || job.optString("state").contains("paused", true)
                        Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(job.optString("name"))
                                LinearProgressIndicator(progress = { job.optDouble("progress").toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(if (done) "Terminé" else "${(job.optDouble("progress") * 100).toInt()} % · ${job.optLong("dlspeed") / 1024} Ko/s", color = Muted)
                                Row {
                                    if (!done) TextButton(enabled = !busy, onClick = { work {
                                        client!!.call("/jobs/$hash/" + (if (paused) "resume" else "pause"), JSONObject())
                                    } }) { Text(if (paused) "Reprendre" else "Pause") }
                                    TextButton(enabled = !busy, onClick = { work {
                                        files = hash to client!!.call("/jobs/$hash/files").optJSONArray("files").cinemaObjects()
                                    } }) { Text("Voir les vidéos") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    files?.let { (hash, list) ->
        AlertDialog(onDismissRequest = { files = null }, title = { Text("Vidéos disponibles") }, text = {
            Column {
                if (list.isEmpty()) Text("Les vidéos apparaîtront une fois téléchargées entièrement.")
                list.take(30).forEach { f ->
                    TextButton(onClick = {
                        playing = "/stream/$hash/${f.getInt("index")}" to f.getString("name")
                        files = null
                    }) { Text(f.getString("name")) }
                }
            }
        }, confirmButton = { TextButton(onClick = { files = null }) { Text("Fermer") } })
    }
    playing?.let { (route, title) -> PcVideoPlayer(client!!, route, title) { playing = null } }
}
private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val block = ByteArray(8192)
    while (true) {
        val n = read(block)
        if (n < 0) break
        require(out.size() + n <= max) { "Fichier trop volumineux" }
        out.write(block, 0, n)
    }
    return out.toByteArray()
}
