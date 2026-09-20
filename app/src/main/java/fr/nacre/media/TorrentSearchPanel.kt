@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** Shared by the cinema search and the torrent download screen. Submission is explicit. */
@Composable
internal fun TorrentSearchPanel(initialQuery: String = "", onQueued: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("torrent_search", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var pcOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var automatic by rememberSaveable { mutableStateOf(prefs.getBoolean("automatic", true)) }
    var sourceConfig by remember { mutableStateOf(TorrentSources(prefs.getBoolean("tpb", true), prefs.getBoolean("archive", true),
        prefs.getString("feeds", "").orEmpty().lines().filter { it.isNotBlank() }, prefs.getString("apikey", "").orEmpty(), prefs.getBoolean("yts", true))) }
    var showSettings by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<TorrentHit>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var sourceErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var generation by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<Job?>(null) }

    suspend fun download(hit: TorrentHit) {
        downloading = true
        try {
            val id = TorrentSearchApi.enqueue(context, hit)
            val current = TorrentStore.active.value.firstOrNull { it.id == id }
            if (current?.state == TorrentState.DONE) message = "Déjà téléchargé : ${hit.title}"
            else {
                message = "Ajouté aux téléchargements : ${hit.title} · ${hit.source}"
            }
            onQueued(id)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Impossible de démarrer ce résultat. Essaie une autre version ou réessaie dans Torrents." }
        finally { downloading = false }
    }
    fun search() {
        if (loading || downloading) return
        val submitted = query.trim()
        if (torrentWords(submitted).length < 2) { message = "Saisis au moins deux caractères."; return }
        request?.cancel()
        val ticket = ++generation
        loading = true; message = "Recherche sur les sources activées…"; results = emptyList(); sourceErrors = emptyList()
        request = scope.launch {
            try {
                val found = TorrentSearchApi.search(submitted, sourceConfig)
                if (ticket != generation) return@launch
                results = found.hits; sourceErrors = found.errors
                val selected = if (automatic) automaticTorrent(submitted, found.hits) else null
                if (selected != null) {
                    message = "Préparation : ${selected.title} · ${selected.source}"
                    download(selected)
                } else message = when {
                    found.hits.isEmpty() && found.errors.isNotEmpty() -> "Aucun résultat reçu. Certaines sources sont indisponibles."
                    found.hits.isEmpty() -> "Aucun torrent trouvé pour ce titre."
                    automatic -> "Aucune correspondance suffisamment précise avec des sources disponibles. Choisis une version ci-dessous."
                    else -> "${found.hits.size} résultats. Choisis une version."
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Recherche impossible. Vérifie les sources et ta connexion." }
            finally { if (ticket == generation) loading = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { pcOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Serveur PC · rechercher et télécharger") }
        Text("Téléchargement sur ce téléphone", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Nom du film, de la série ou de l’anime") },
            singleLine = true, enabled = !loading && !downloading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
        Row(Modifier.fillMaxWidth()) {
            Text("Téléchargement automatique à la validation", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(automatic, { automatic = it; prefs.edit().putBoolean("automatic", it).apply() }, enabled = !loading && !downloading)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { search() }, enabled = !loading && !downloading && query.isNotBlank()) { Text(if (automatic) "Chercher et télécharger" else "Rechercher") }
            TextButton(onClick = { showSettings = true }, enabled = !loading && !downloading) { Text("Sources") }
        }
        Text(listOfNotNull(if (sourceConfig.pirateBay) "The Pirate Bay" else null, if (sourceConfig.archive) "Internet Archive" else null,
            if (sourceConfig.yts) "YTS" else null, if (sourceConfig.feeds.isNotEmpty()) "${sourceConfig.feeds.size} flux Torznab" else null).joinToString(" · ").ifBlank { "Aucune source activée" },
            style = MaterialTheme.typography.bodySmall, color = Muted)
        if (loading || downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (loading && !downloading) TextButton(onClick = { generation++; request?.cancel(); loading = false; message = "Recherche annulée." }) { Text("Annuler la recherche") }
        if (message.isNotBlank()) Text(message, color = Lime, style = MaterialTheme.typography.bodySmall)
        sourceErrors.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        results.take(12).forEach { hit ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(hit.title, style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(hit.source, hit.seeders?.let { "$it sources complètes" }, hit.bytes?.takeIf { it > 0 }?.let { "%.2f Go".format(it / 1_073_741_824.0) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = { scope.launch { download(hit) } }, enabled = !loading && !downloading) { Text("Télécharger cette version") }
            }
        }
    }
    if (pcOpen) PcTorrentScreen { pcOpen = false }
    if (showSettings) TorrentSourceSettings(sourceConfig, { config ->
        prefs.edit().putBoolean("tpb", config.pirateBay).putBoolean("archive", config.archive)
            .putBoolean("yts", config.yts).putString("feeds", config.feeds.joinToString("\n")).putString("apikey", config.apiKey).apply()
        sourceConfig = config; showSettings = false
    }) { showSettings = false }
}

@Composable
private fun TorrentSourceSettings(config: TorrentSources, onSave: (TorrentSources) -> Unit, onDismiss: () -> Unit) {
    var yts by remember { mutableStateOf(config.yts) }
    var tpb by remember { mutableStateOf(config.pirateBay) }
    var archive by remember { mutableStateOf(config.archive) }
    var feeds by remember { mutableStateOf(config.feeds.joinToString("\n")) }
    var key by remember { mutableStateOf(config.apiKey) }
    val urls = feeds.lines().map { it.trim() }.filter { it.isNotBlank() }
    val valid = urls.size <= 8 && urls.all(::validStreamUrl) && (tpb || archive || yts || urls.isNotEmpty())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sources torrent") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Text("The Pirate Bay / APIbay", Modifier.weight(1f)); Switch(tpb, { tpb = it }) }
            Row { Text("Internet Archive", Modifier.weight(1f)); Switch(archive, { archive = it }) }
            Row { Text("YTS · films", Modifier.weight(1f)); Switch(yts, { yts = it }) }
            Text("1337x et autres sites : ajoute les URL des flux Torznab de ton serveur Jackett ou Prowlarr. Pour 1337x, active cet indexeur dans le serveur et copie son URL Torznab ici. Le serveur doit être déjà configuré avec tes indexeurs et accessible depuis ce téléphone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(feeds, { feeds = it }, label = { Text("Flux Torznab HTTPS, un par ligne") }, minLines = 2, maxLines = 5, isError = !valid)
            OutlinedTextField(key, { key = it }, label = { Text("Clé API Torznab commune (facultative)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("Maximum 8 flux. Si les clés diffèrent, utilise les URL complètes fournies par chaque serveur et laisse la clé commune vide. Les réglages restent privés et ne sont pas inclus dans les exports Echo-All.", style = MaterialTheme.typography.bodySmall)
            Text("Les sources peuvent être indisponibles ou bloquées selon le réseau. Une recherche porte sur les vidéos ; elle ne couvre pas tous les sites torrent existants.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(TorrentSources(tpb, archive, urls, key.trim(), yts)) }, enabled = valid) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
