@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TorrentCrashDiagnostic() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    TextButton(enabled = !loading, onClick = {
        loading = true; copied = false
        scope.launch {
            report = withContext(Dispatchers.IO) {
                runCatching { torrentCrashReport(context) }.getOrDefault("Impossible de lire le diagnostic Android.")
            }
            loading = false
        }
    }) { Text("Diagnostic du dernier arrêt") }
    report?.let { text ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("Diagnostic du dernier arrêt") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnostic Echo-All", text))
                copied = true
            }) { Text(if (copied) "Copié" else "Copier") } },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Fermer") } })
    }
}
