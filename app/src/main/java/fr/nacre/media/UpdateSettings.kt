package fr.nacre.media

import android.app.DownloadManager
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where the release manifest lives, and what to do with what it announces.
 *
 * The address is a setting rather than something built in, because the source repository is
 * private: a token baked into the application would simply be a published token. Pointing this at
 * the PC server on the local network keeps everything off the open internet.
 */
@Composable
fun UpdateSettings(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { installedVersionCode(context) }
    var url by remember { mutableStateOf(prefs.getString("updateUrl", "").orEmpty()) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var ready by remember { mutableStateOf(false) }

    fun look(quiet: Boolean) {
        val address = url.trim()
        if (address.isBlank() || busy) return
        busy = true; if (!quiet) message = ""
        scope.launch {
            try {
                val info = Updates.check(context, address)
                found = info
                message = when {
                    updateAvailable(installed, info) -> ""
                    quiet -> ""
                    else -> "Tu es à jour."
                }
            } catch (error: Exception) {
                if (!quiet) message = error.message ?: "Vérification impossible."
            } finally { busy = false }
        }
    }

    // Once a day, silently. A failure here is not worth interrupting anyone for.
    LaunchedEffect(Unit) { if (Updates.dueForCheck(prefs)) look(quiet = true) }

    // While a download runs, watch it rather than make the user come back and check.
    LaunchedEffect(downloadId) {
        if (downloadId < 0) return@LaunchedEffect
        while (true) {
            when (Updates.downloadState(context, downloadId)) {
                DownloadManager.STATUS_SUCCESSFUL -> { ready = true; return@LaunchedEffect }
                DownloadManager.STATUS_FAILED -> { message = "Téléchargement interrompu."; downloadId = -1; return@LaunchedEffect }
            }
            delay(700)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(url, { url = it; prefs.edit().putString("updateUrl", it.trim()).apply(); found = null },
            label = { Text("Adresse du manifeste") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…/version.json") })
        Text("Version installée : $installed. L’application regarde une fois par jour ; l’adresse doit être en HTTPS.",
            color = Muted, fontSize = 12.sp)

        val update = found?.takeIf { updateAvailable(installed, it) }
        if (update != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${update.versionName} disponible", color = Lime)
                    if (update.notes.isNotBlank()) Text(update.notes, color = Muted, fontSize = 12.sp)
                    when {
                        ready -> Button(onClick = {
                            Updates.installer(context, downloadId)?.let { context.startActivity(it) }
                                ?: run { message = "Fichier introuvable." }
                        }) { Text("Installer") }
                        downloadId >= 0 -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> Button(onClick = {
                            runCatching { downloadId = Updates.download(context, update) }
                                .onFailure { message = "Téléchargement impossible." }
                        }) { Text("Télécharger") }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { look(quiet = false) }, enabled = url.isNotBlank() && !busy) {
                Text(if (busy) "Vérification…" else "Vérifier maintenant")
            }
            if (message.isNotBlank()) Text(message, color = Muted, fontSize = 12.sp)
        }
    }
}
