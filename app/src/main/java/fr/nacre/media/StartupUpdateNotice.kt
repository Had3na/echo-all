package fr.nacre.media

import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException

@Composable
fun StartupUpdateNotice(prefs: SharedPreferences) {
    val context = LocalContext.current
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED && Updates.dueForCheck(prefs)) {
            try {
                val found = Updates.check(context, Updates.manifestUrl(prefs))
                if (updateAvailable(installedVersionCode(context), found)) update = found
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Offline: retry on a later foreground transition. */ }
        }
    }
    update?.let { found ->
        AlertDialog(onDismissRequest = { update = null },
            title = { Text("Echo-All ${found.versionName} disponible") },
            text = { Text(found.notes.ifBlank { "Une nouvelle version est disponible sur GitHub." }) },
            confirmButton = { TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found.apkUrl)))
                update = null
            }) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Plus tard") } })
    }
}
