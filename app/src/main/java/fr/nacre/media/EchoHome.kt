package fr.nacre.media

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun EchoHome(library: List<LibraryItem>, prefs: SharedPreferences, onCollection: (String?) -> Unit, onPlay: (LibraryItem) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    var lists by remember { mutableStateOf(store.lists()) }
    var revision by remember { mutableIntStateOf(0) }
    var customize by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(store, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val stopStudio = StudioEvents.listen { key -> if (key == "lists") lists = store.lists() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { stopStudio(); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val sections = remember(revision) { homeSections(prefs.getString("homeOrder", "playlists,recent,favorites").orEmpty()) }
    val hidden = remember(revision) { prefs.getStringSet("homeHidden", emptySet()).orEmpty().toSet() }
    val title = remember(revision) { prefs.getString("homeTitle", "Mon espace").orEmpty() }
    val count = remember(revision) { prefs.getInt("homeCount", 6).coerceIn(3, 20) }
    val compact = remember(revision) { prefs.getBoolean("homeCompact", false) }
    val wide = remember(revision) { prefs.getString("homeShape", "card") == "wide" }
    val labels = mapOf("playlists" to "Mes playlists", "recent" to "Ajouts récents", "favorites" to "Mes favoris", "resume" to "Reprendre")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp), contentPadding = PaddingValues(bottom = 160.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { "Mon espace" }, fontSize = 24.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { customize = true }) { Icon(Icons.Rounded.DashboardCustomize, "Personnaliser l’accueil") }
        } }
        if (library.isEmpty()) item { OutlinedButton(onClick = onScan) { Text("Scanner les médias du téléphone") } }
        sections.filterNot { it in hidden }.forEach { section ->
            item(key = "heading:$section") { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels.getValue(section), fontSize = 19.sp, modifier = Modifier.weight(1f))
                if (section == "playlists") TextButton(onClick = { onCollection(null) }) { Text("Créer") }
            } }
            if (section == "playlists") {
                if (lists.isEmpty()) item { Text("Crée ta première playlist : elle apparaîtra ici immédiatement.", color = Muted) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                    items(lists, key = { it.id }) { list ->
                        PlayingMediaCard(list.name.ifBlank { "Sans titre" }, list.uris.size.toString()+" médias" + if (list.shuffle) " · Aléatoire" else "", wide, compact, { onCollection(list.id) }) {
                            val cover = rememberCover("list:" + list.id)
                            if (cover != null) coil.compose.AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(if (wide) 32.dp else 52.dp), tint = Lime)
                        }
                    }
                } }
            } else {
                val media = (when (section) { "favorites" -> library.filter { it.favorite }; "resume" -> library.filter { it.uri == context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE).getString("last", null) }; else -> library.sortedByDescending { it.addedAt } }).take(count)
                if (media.isEmpty()) item { Text("Aucun média pour le moment.", color = Muted) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                    items(media, key = { it.uri }) { entry ->
                        PlayingMediaCard(entry.title, entry.artist.ifBlank { entry.source }, wide, compact, { onPlay(entry) }) {
                            MediaThumbnail(entry, Modifier.fillMaxSize())
                        }
                    }
                } }
            }
        }
        if (sections.all { it in hidden }) item { Text("Accueil vide : utilise le bouton Personnaliser pour afficher des sections.", color = Muted) }
    }
    if (customize) Dialog(onDismissRequest = { customize = false }) {
        Surface(shape = RoundedCornerShape(24.dp), color = Ink) {
            LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Ton accueil", fontSize = 24.sp) }
                item { OutlinedTextField(title, { prefs.edit().putString("homeTitle", it.take(60)).apply() }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("Afficher et organiser les sections", color = Lime) }
                items(sections, key = { it }) { section ->
                    val index = sections.indexOf(section)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(section !in hidden, { checked -> prefs.edit().putStringSet("homeHidden", if (checked) hidden - section else hidden + section).apply() })
                        Text(labels.getValue(section), modifier = Modifier.weight(1f))
                        IconButton(enabled = index > 0, onClick = { val order = sections.toMutableList(); java.util.Collections.swap(order, index, index - 1); prefs.edit().putString("homeOrder", order.joinToString(",")).apply() }) { Icon(Icons.Rounded.ArrowUpward, "Monter") }
                        IconButton(enabled = index < sections.lastIndex, onClick = { val order = sections.toMutableList(); java.util.Collections.swap(order, index, index + 1); prefs.edit().putString("homeOrder", order.joinToString(",")).apply() }) { Icon(Icons.Rounded.ArrowDownward, "Descendre") }
                    }
                }
                item { Text("Forme des cartes", color = Lime)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!wide, { prefs.edit().putString("homeShape", "card").apply() }, { Text("Carte") })
                        FilterChip(wide, { prefs.edit().putString("homeShape", "wide").apply() }, { Text("Rectangle long") })
                    } }
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Cartes compactes", modifier = Modifier.weight(1f)); Switch(compact, { prefs.edit().putBoolean("homeCompact", it).apply() }) } }
                item { Text("$count médias par section"); Slider(count.toFloat(), { prefs.edit().putInt("homeCount", it.toInt()).apply() }, valueRange = 3f..20f, steps = 16) }
                item { Row { TextButton(onClick = { prefs.edit().remove("homeOrder").remove("homeHidden").remove("homeTitle").remove("homeCount").remove("homeCompact").remove("homeShape").apply() }) { Text("Réinitialiser") }; Button(onClick = { customize = false }) { Text("Terminé") } } }
            }
        }
    }
}

fun homeSections(value: String): List<String> {
    val known = listOf("playlists", "recent", "favorites", "resume")
    return (value.split(',').filter { it in known } + known).distinct()
}
