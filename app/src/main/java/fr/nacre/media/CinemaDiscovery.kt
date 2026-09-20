@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

import java.io.IOException

private fun cinemaError(error: Exception): String =
    if (error is IOException && error.message?.let { !it.contains("http", true) && !it.contains("Exception") } == true)
        error.message.orEmpty() else "Connexion au catalogue impossible. Réessaie."
private fun cinemaBrowse(context: Context, url: String) {
    if (!validStreamUrl(url)) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "Aucune application pour ouvrir ce lien.", Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun CinemaDiscovery(onPlay: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // API credentials are kept outside the settings exported by Backup.kt.
    val credentials = remember { context.getSharedPreferences("cinema_api", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(credentials.getString("tmdb_token", "").orEmpty()) }
    var country by remember { mutableStateOf(cinemaCountry(credentials.getString("country", "FR").orEmpty())) }
    val api = remember(token) { CinemaApi(token) }
    var torrentMode by rememberSaveable { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(CinemaKind.ANIME) }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<CinemaTitle>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CinemaTitle?>(null) }
    LaunchedEffect(api, kind, query, page, revision, torrentMode) {
        if (torrentMode) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        if (page == 1) { entries = emptyList(); more = false }
        try {
            val result = api.search(kind, query, page)
            entries = (if (page == 1) result.entries else entries + result.entries).distinctBy { it.id }
            more = result.more
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cinemaError(e) }
        finally { loading = false }
    }
    fun search() { query = input.trim(); page = 1; revision++ }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp),
                contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onDismiss) { Text("Retour") }
                        TextButton(onClick = { settings = true }) { Text("Réglages API · $country") }
                    }
                    Text("Films, séries et animes", style = MaterialTheme.typography.headlineSmall)
                }
                if (!torrentMode) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CinemaKind.entries) { tab ->
                            FilterChip(kind == tab, { kind = tab; page = 1; entries = emptyList() }, label = { Text(tab.label) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!torrentMode, { torrentMode = false }, label = { Text("Fiches") })
                        FilterChip(torrentMode, { torrentMode = true }, label = { Text("Torrents · téléchargement") })
                    }
                }
                if (torrentMode) item { TorrentSearchPanel(initialQuery = input) }
                if (!torrentMode) item {
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Rechercher un titre") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                    TextButton(onClick = { search() }) { Text("Rechercher") }
                }
                if (!torrentMode && kind != CinemaKind.ANIME && token.isBlank()) item {
                    Text("Connecte TMDB pour les films, les séries et leurs plateformes. Les animes sont accessibles sans clé.")
                    Button(onClick = { settings = true }) { Text("Configurer TMDB") }
                }
                if (!torrentMode && error.isNotBlank()) item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { revision++ }) { Text("Réessayer") }
                }
                if (!torrentMode && !loading && error.isBlank() && entries.isEmpty()) item { Text("Aucun résultat.") }
                items(if (torrentMode) emptyList() else entries, key = { "${it.kind}:${it.id}" }) { title ->
                    Row(Modifier.fillMaxWidth().clickable { selected = title }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(title.poster, "Affiche de ${title.title}", Modifier.width(80.dp).height(115.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(title.title, fontWeight = FontWeight.SemiBold)
                            Text(listOf(title.year, title.rating).filter { it.isNotBlank() }.joinToString(" · "), color = Muted)
                            Text(title.overview.ifBlank { "Ouvrir la fiche" }, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!torrentMode && loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
                if (!torrentMode && more && !loading && error.isBlank()) item { Button(onClick = { page++ }) { Text("Afficher la suite") } }
                item {
                    Text(if (kind == CinemaKind.ANIME) "Métadonnées : Jikan / MyAnimeList. Les épisodes listés ne sont pas des liens vidéo."
                        else "Métadonnées : TMDB. Disponibilités : JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
    if (settings) CinemaApiSettings(token, country, { value, region ->
        credentials.edit().putString("tmdb_token", value).putString("country", region).apply()
        token = value; country = region; page = 1; settings = false
    }, { settings = false })
    selected?.let { title -> key(title.kind, title.id, api, country) {
        CinemaDetails(title, api, country, onPlay) { selected = null }
    } }
}

@Composable
private fun CinemaApiSettings(token: String, country: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(token) }
    var region by remember { mutableStateOf(country) }
    val validCountry = region.trim().uppercase(java.util.Locale.ROOT) in java.util.Locale.getISOCountries().toSet()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Catalogues et plateformes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jikan fonctionne sans clé. Pour TMDB, utilise le jeton d’accès en lecture de ton compte (API Read Access Token).")
            OutlinedTextField(draft, { draft = it }, label = { Text("Jeton TMDB") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = { cinemaBrowse(context, "https://www.themoviedb.org/settings/api") }) { Text("Obtenir un jeton TMDB") }
            OutlinedTextField(region, { region = it.take(2) }, label = { Text("Pays (FR, AE, BE…)") }, singleLine = true, isError = !validCountry)
            Text("Crédits", fontWeight = FontWeight.SemiBold)
            Image(painterResource(R.drawable.tmdb_logo), "TMDB", Modifier.width(137.dp).aspectRatio(273.42f / 35.52f))
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB. Disponibilités : JustWatch. Animes : Jikan / MyAnimeList.", style = MaterialTheme.typography.bodySmall)
            Text("Le jeton reste sur cet appareil et n’est pas inclus dans les exports Echo-All. Efface-le ici pour déconnecter TMDB.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft.trim().removePrefix("Bearer "), cinemaCountry(region)) }, enabled = validCountry && !draft.contains('\n') && !draft.contains('\r')) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun CinemaDetails(initial: CinemaTitle, api: CinemaApi, country: String, onPlay: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var availability by remember { mutableStateOf<CinemaAvailability?>(null) }
    var providerError by remember { mutableStateOf("") }
    var providerRevision by remember { mutableIntStateOf(0) }
    var season by remember { mutableStateOf<Int?>(null) }
    var episodePage by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf<List<CinemaEpisode>>(emptyList()) }
    var episodeMore by remember { mutableStateOf(false) }
    var episodeLoading by remember { mutableStateOf(false) }
    var episodeError by remember { mutableStateOf("") }
    var episodeRevision by remember { mutableIntStateOf(0) }
    var streamFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        loading = true; error = ""
        try { title = api.details(initial); if (season == null) season = title.seasons.firstOrNull { it > 0 } ?: title.seasons.firstOrNull() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = cinemaError(e) }
        finally { loading = false }
    }
    LaunchedEffect(providerRevision) {
        if (initial.kind != CinemaKind.ANIME) {
            providerError = ""
            try { availability = api.availability(initial, country) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { providerError = cinemaError(e) }
        }
    }
    LaunchedEffect(season, episodePage, episodeRevision) {
        if (initial.kind == CinemaKind.MOVIE || (initial.kind == CinemaKind.TV && season == null)) return@LaunchedEffect
        episodeLoading = true; episodeError = ""
        if (episodePage == 1) { episodes = emptyList(); episodeMore = false }
        try {
            val result = api.episodes(initial, season ?: 1, episodePage)
            episodes = (if (episodePage == 1) result.entries else episodes + result.entries).distinctBy { it.number }
            episodeMore = result.more
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { episodeError = cinemaError(e) }
        finally { episodeLoading = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { TextButton(onClick = onDismiss) { Text("Retour au catalogue") } }
                item {
                    AsyncImage(title.poster, "Affiche", Modifier.fillMaxWidth().height(230.dp), contentScale = ContentScale.Fit)
                    Text(title.title, style = MaterialTheme.typography.headlineSmall)
                    Text(listOf(title.year, title.rating, title.genres.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "), color = Muted)
                }
                if (loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                if (error.isNotBlank()) item { Text(error); TextButton(onClick = { revision++ }) { Text("Recharger la fiche") } }
                item { Text(title.overview.ifBlank { "Aucun résumé disponible." }) }
                if (title.trailer.isNotBlank()) item { Button(onClick = { cinemaBrowse(context, title.trailer) }) { Text("Bande-annonce sur YouTube") } }
                if (title.kind != CinemaKind.ANIME) {
                    item { Text("Où regarder · $country", style = MaterialTheme.typography.titleMedium) }
                    if (providerError.isNotBlank()) item { Text(providerError); TextButton(onClick = { providerRevision++ }) { Text("Recharger les plateformes") } }
                    val offers = availability
                    if (offers == null && providerError.isBlank()) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                    if (offers != null) {
                        if (offers.offers.isEmpty()) item { Text("Aucune disponibilité renseignée pour ce pays.") }
                        items(offers.offers) { Text("${it.name} · ${it.type}") }
                        if (offers.link.isNotBlank()) item { Button(onClick = { cinemaBrowse(context, offers.link) }) { Text("Ouvrir les offres") } }
                    }
                    item { Text("Disponibilités fournies par JustWatch via TMDB. Abonnement ou paiement possible selon l’offre.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
                if (title.kind != CinemaKind.MOVIE) {
                    item { Text("Épisodes", style = MaterialTheme.typography.titleMedium) }
                    if (title.seasons.isNotEmpty()) item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(title.seasons) { n -> FilterChip(season == n, { season = n; episodePage = 1; episodes = emptyList() }, label = { Text(if (n == 0) "Spéciaux" else "Saison $n") }) }
                        }
                    }
                    if (episodeError.isNotBlank()) item { Text(episodeError); TextButton(onClick = { episodeRevision++ }) { Text("Recharger les épisodes") } }
                    items(episodes, key = { it.number }) { episode ->
                        Column {
                            Text("${episode.number}. ${episode.title.ifBlank { "Épisode ${episode.number}" }}", fontWeight = FontWeight.Medium)
                            if (episode.date.isNotBlank()) Text(episode.date, color = Muted)
                            if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { streamFor = "${title.title} · " + (if (title.kind == CinemaKind.TV) "S${season ?: 1} " else "") + "E${episode.number}" }) { Text("Lire mon lien vidéo") }
                        }
                    }
                    if (episodeLoading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                    if (!loading && !episodeLoading && episodeError.isBlank() && episodes.isEmpty()) item { Text("Aucun épisode renseigné.") }
                    if (episodeMore && !episodeLoading && episodeError.isBlank()) item { Button(onClick = { episodePage++ }) { Text("Épisodes suivants") } }
                }
                item {
                    Text("Tu disposes d’un lien vidéo direct ?", style = MaterialTheme.typography.titleMedium)
                    Text("Les catalogues ne fournissent pas de flux vidéo. Tu peux ouvrir un lien HTTPS vers une vidéo ou un flux HLS dans le lecteur Echo-All.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { streamFor = title.title }) { Text("Lire mon lien vidéo") }
                }
                item { TextButton(onClick = { cinemaBrowse(context, if (title.kind == CinemaKind.ANIME) "https://myanimelist.net/anime/${title.id}" else "https://www.themoviedb.org/${title.kind.path}/${title.id}") }) { Text(if (title.kind == CinemaKind.ANIME) "Fiche MyAnimeList · Jikan" else "Fiche TMDB") } }
            }
        }
    }
    streamFor?.let { name ->
        var url by remember(name) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { streamFor = null }, title = { Text(name) }, text = {
            Column {
                Text("Colle le lien direct (MP4, HLS .m3u8…). Une page d’hébergeur n’est pas un flux vidéo.")
                OutlinedTextField(url, { url = it }, label = { Text("https://…") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { onPlay(url.trim(), name); streamFor = null }, enabled = validStreamUrl(url)) { Text("Lire") } },
            dismissButton = { TextButton(onClick = { streamFor = null }) { Text("Annuler") } })
    }
}
