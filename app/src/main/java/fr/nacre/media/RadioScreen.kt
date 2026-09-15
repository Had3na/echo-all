package fr.nacre.media

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val RADIO_SHORTCUTS = listOf(
    Triple("Populaires en France", "", "FR"), Triple("Monde", "", ""), Triple("Pop", "pop", ""), Triple("Rock", "rock", ""),
    Triple("Électro", "electronic", ""), Triple("Rap", "hip-hop", ""), Triple("Jazz", "jazz", ""), Triple("Classique", "classical", ""),
    Triple("Lo-fi", "lofi", ""), Triple("Info", "news", "FR")
)

/** Internet radios from the Radio Browser directory: listen right away or keep them in the library. */
@Composable
fun RadioScreen(vm: LibraryViewModel, player: Player?, library: List<LibraryItem>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var shortcut by remember { mutableIntStateOf(0) }
    var stations by remember { mutableStateOf<List<RadioStation>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val saved = remember(library) { library.map { it.uri }.toSet() }

    fun load(text: String, tag: String, country: String) {
        loading = true; message = ""
        scope.launch {
            try { stations = Online.searchRadios(text, tag, country); if (stations.isNullOrEmpty()) message = "Aucune radio HTTPS trouvée." }
            catch (_: Exception) { message = "Annuaire des radios injoignable. Vérifie ta connexion." }
            finally { loading = false }
        }
    }
    fun listen(station: RadioStation) {
        val controller = player ?: run { message = "Le lecteur démarre… Réessaie dans un instant."; return }
        controller.setMediaItem(MediaItem.Builder().setMediaId(station.url).setUri(station.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist("Radio · " + station.country)
                .setArtworkUri(station.favicon.takeIf { it.isNotBlank() }?.let(Uri::parse))
                .setExtras(Bundle().apply { putBoolean("video", false) }).build()).build())
        controller.prepare(); controller.play()
        message = "En écoute : ${station.name}"
        scope.launch { Online.countRadioClick(station.uuid) }
    }
    LaunchedEffect(Unit) { RADIO_SHORTCUTS[0].let { load("", it.second, it.third) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Radios", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer") }
                } }
                item { OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Nom d’une radio…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { shortcut = -1; load(query, "", "") })) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RADIO_SHORTCUTS.forEachIndexed { index, (label, tag, country) ->
                        FilterChip(shortcut == index, { shortcut = index; query = ""; load("", tag, country) }, { Text(label) })
                    }
                } }
                item { Text("Annuaire libre Radio Browser. Seuls les flux sécurisés (HTTPS) sont proposés.", color = Muted, fontSize = 11.sp) }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (message.isNotBlank()) item { Text(message, color = Lime) }
                items(stations.orEmpty(), key = { it.url }) { station ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable { listen(station) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val placeholder = rememberVectorPainter(Icons.Rounded.Radio)
                        AsyncImage(station.favicon.ifBlank { null }, null, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Ink),
                            contentScale = ContentScale.Crop, placeholder = placeholder, error = placeholder, fallback = placeholder)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(station.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(station.details, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { listen(station) }) { Icon(Icons.Rounded.PlayArrow, "Écouter ${station.name}", tint = Lime) }
                        if (station.url in saved) Icon(Icons.Rounded.Check, "Déjà dans ta bibliothèque", Modifier.padding(12.dp), tint = Muted)
                        else IconButton(onClick = { vm.addRadio(station) }) { Icon(Icons.Rounded.Add, "Ajouter ${station.name} à ta bibliothèque") }
                    }
                }
            }
        }
    }
}
