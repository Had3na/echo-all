@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private fun torrentSize(bytes: Long): String = if (bytes >= 1_073_741_824) "%.2f Go".format(bytes / 1_073_741_824.0) else "%.1f Mo".format(bytes / 1_048_576.0)

@Composable
internal fun TorrentScreen(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by TorrentStore.active.collectAsState()
    var magnet by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf<String?>(null) }
    var filesFor by remember { mutableStateOf<TorrentJob?>(null) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { TorrentStore.initialize(context) }; ready = true }
        catch (e: Exception) { message = "Impossible de charger les torrents : ${e.message}" }
    }
    fun launch(id: String) {
        try {
            TorrentDownloadService.start(context, id)
            if (Build.VERSION.SDK_INT >= 33 && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            message = "Impossible de démarrer le téléchargement. Réessaie depuis cet écran."
            scope.launch(Dispatchers.IO) { TorrentStore.change(id) { it.copy(state = TorrentState.FAILED, error = message) } }
        }
    }
    fun add(uri: Uri? = null) {
        if (adding || !ready) return
        adding = true; message = ""
        scope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val source = if (uri == null) torrentMagnet(magnet) else ""
                withContext(Dispatchers.IO) {
                    if (uri != null) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val block = ByteArray(8192)
                            while (true) {
                                val n = input.read(block)
                                if (n < 0) break
                                require(output.size() + n <= MAX_TORRENT_BYTES) { "Fichier .torrent trop volumineux (maximum 4 Mo)." }
                                output.write(block, 0, n)
                            }
                            output.toByteArray() }
                            ?: error("Fichier inaccessible.")
                        require(bytes.size in 1..MAX_TORRENT_BYTES && bytes.first() == 'd'.code.toByte()) { "Choisis un fichier .torrent valide (4 Mo maximum)." }
                        val target = TorrentStore.metadata(context, id)
                        target.parentFile?.mkdirs(); target.writeBytes(bytes)
                    }
                    TorrentStore.add(TorrentJob(id, if (uri == null) torrentDisplayName(source) else "Fichier torrent", source))
                }
                magnet = ""; launch(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Impossible d’ajouter ce torrent." }
            finally { adding = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) add(uri) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Torrents", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = onDismiss) { Text("Fermer") }
                    }
                    Text("Ajoute un magnet ou un fichier .torrent. Un téléchargement à la fois ; les autres attendent leur tour.")
                }
                item { TorrentCrashDiagnostic() }
                item { TorrentSearchPanel(onQueued = { message = "Téléchargement ajouté à la liste ci-dessous." }) }
                item {
                    OutlinedTextField(magnet, { magnet = it }, Modifier.fillMaxWidth(), label = { Text("Lien magnet") }, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { add() }, enabled = ready && !adding && magnet.isNotBlank()) { Text("Télécharger") }
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = ready && !adding) { Text("Fichier .torrent") }
                    }
                    if (adding) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                item {
                    Text("BitTorrent échange des données avec d’autres pairs pendant le téléchargement, puis Echo-All arrête le partage à la fin. Le réseau mobile peut être utilisé.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("Les fichiers sont conservés dans l’espace de l’application et supprimés si elle est désinstallée. Utilise « Enregistrer sous » pour garder une copie ailleurs.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (message.isNotBlank()) item { Text(message, color = Lime) }
                if (ready && jobs.isEmpty()) item { Text("Aucun torrent ajouté.", color = Muted) }
                items(jobs, key = { it.id }) { job ->
                    Surface(color = Panel, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.title, style = MaterialTheme.typography.titleMedium)
                            Text(when (job.state) {
                                TorrentState.QUEUED -> "En attente"
                                TorrentState.METADATA -> "Recherche des métadonnées et des pairs…"
                                TorrentState.CHECKING -> "Vérification des fichiers déjà présents…"
                                TorrentState.RUNNING -> if (job.peers == 0) "En attente de pairs…" else "${job.peers} pairs · ${torrentSize(job.speed)}/s"
                                TorrentState.STOPPING -> "Mise en pause et fermeture du moteur…"
                                TorrentState.PAUSED -> "En pause · fichiers conservés"
                                TorrentState.DONE -> "Terminé · ${job.files.size} fichiers"
                                TorrentState.FAILED -> job.error.ifBlank { "Échec du téléchargement" }
                            }, style = MaterialTheme.typography.bodySmall)
                            if (job.total > 0) {
                                LinearProgressIndicator(progress = { (job.downloaded.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text("${torrentSize(job.downloaded)} / ${torrentSize(job.total)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (job.active) TextButton(onClick = {
                                runCatching { TorrentDownloadService.pause(context, job.id) }.onFailure { message = "Impossible de mettre en pause." }
                            }) { Text("Mettre en pause") }
                            else if (job.state in listOf(TorrentState.PAUSED, TorrentState.FAILED)) TextButton(onClick = { launch(job.id) }) { Text(if (job.state == TorrentState.FAILED) "Réessayer" else "Reprendre") }
                            if (job.state == TorrentState.DONE) {
                                TextButton(onClick = { filesFor = job }) { Text("Voir / enregistrer les fichiers") }
                                TextButton(onClick = { vm.collectTorrent(job); message = "Ajout des médias à la bibliothèque…" }) { Text("Ajouter les médias à ma bibliothèque") }
                            }
                        }
                    }
                }
                item { TextButton(onClick = {
                    scope.launch {
                        licenses = withContext(Dispatchers.IO) {
                            listOf("libtorrent4j", "libtorrent", "openssl", "boost").joinToString("\n\n") { name ->
                                name + "\n" + context.assets.open("torrent-licenses/$name.txt").bufferedReader().use { it.readText() }
                            }
                        }
                    }
                }) { Text("Moteur torrent · licences") } }
            }
        }
    }
    filesFor?.let { TorrentFiles(it) { filesFor = null } }
    licenses?.let { text -> AlertDialog(onDismissRequest = { licenses = null }, title = { Text("Licences du moteur torrent") },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { licenses = null }) { Text("Fermer") } }) }
}

@Composable
private fun TorrentFiles(job: TorrentJob, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val relative = export
        export = null
        if (uri != null && relative != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val source = torrentChild(TorrentStore.folder(context, job.id), relative)
                        require(source.isFile) { "Fichier introuvable." }
                        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Destination inaccessible.")
                        out.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                    }
                    message = "Copie enregistrée."
                } catch (e: Exception) { message = "Copie incomplète : ${e.message}" }
                finally { busy = false }
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(job.title, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onDismiss, enabled = !busy) { Text("Fermer") } }
                if (message.isNotBlank()) item { Text(message) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Copie en cours, garde cet écran ouvert.") }
                items(job.files, key = { it }) { path ->
                    Column {
                        Text(path)
                        Row {
                            TextButton(onClick = {
                                runCatching {
                                    val uri = TorrentStore.fileUri(context, job, path)
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, torrentMime(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { message = "Aucune application compatible ou fichier inaccessible." }
                            }, enabled = !busy) { Text("Ouvrir") }
                            TextButton(onClick = { export = path; destination.launch(File(path).name) }, enabled = !busy && export == null) { Text("Enregistrer sous") }
                        }
                    }
                }
            }
        }
    }
}
