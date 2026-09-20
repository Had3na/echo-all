package fr.nacre.media

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val VIDEO_SECTIONS = listOf("daily", "stream")
fun LibraryItem.videoSpace(): String = videoSection.takeIf { it in VIDEO_SECTIONS } ?: if (uri.startsWith("https://", true)) "stream" else "daily"
fun LibraryItem.videoGroup(): String = videoCategory.ifBlank { "Non classées" }
fun videoGroups(items: List<LibraryItem>, section: String): List<String> =
    (listOf("Non classées") + (if(section == "stream") listOf("Films", "Séries", "Documentaires", "Clips", "Émissions") else listOf("Famille", "Voyages", "Souvenirs")) + items.filter { it.kind == MediaKind.VIDEO && it.videoSpace() == section }.map { it.videoGroup() }).distinct()

@Composable
fun VideoFilters(section: String, category: String, library: List<LibraryItem>, onSection: (String) -> Unit, onCategory: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("daily" to "Quotidien", "stream" to "Streaming").forEach { (key,label) ->
                FilterChip(section == key, { onSection(key) }, { Text(label) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Toutes") + videoGroups(library, section)).forEach { name -> FilterChip(category == name, { onCategory(name) }, { Text(name) }) }
        }
    }
}

@Composable
fun VideoCategoryDialog(item: LibraryItem, library: List<LibraryItem>, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var section by rememberSaveable(item.uri) { mutableStateOf(item.videoSpace()) }
    var category by rememberSaveable(item.uri) { mutableStateOf(item.videoCategory) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Classer cette vidéo") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, maxLines = 2)
            VideoFilters(section, category.ifBlank { "Non classées" }, library, { section = it; category = "" }, { category = if(it == "Toutes" || it == "Non classées") "" else it })
            OutlinedTextField(category, { category = it.take(48) }, label = { Text("Catégorie personnalisée") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Choisis une catégorie ou donne-lui un nouveau nom.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(section, category); onDismiss() }) { Text("Enregistrer") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
