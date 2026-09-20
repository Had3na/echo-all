@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package fr.nacre.media

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val Lime: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun NacreTheme(content: @Composable () -> Unit) {
    val (settings) = rememberPreferences()
    val dark = when(settings.theme) { "light" -> false; "system" -> androidx.compose.foundation.isSystemInDarkTheme(); else -> true }
    val selected = Color(settings.color)
    val accent = if(dark) { if(selected.luminance()<.18f) lerp(selected,Color.White,.48f) else selected } else { if(selected.luminance()>.3f) lerp(selected,Color.Black,.45f) else selected }
    val background = if(dark) lerp(Color(0xFF101114),selected,.035f) else lerp(Color(0xFFFAFAFC),selected,.025f)
    val palette = if(dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        typography = Typography(
            headlineLarge = TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=32.sp,lineHeight=38.sp,letterSpacing=(-1).sp),
            titleLarge = TextStyle(fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=28.sp,letterSpacing=(-.5).sp),
            titleMedium = TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
            bodyLarge = TextStyle(fontSize=16.sp,lineHeight=24.sp),
            bodyMedium = TextStyle(fontSize=14.sp,lineHeight=21.sp),
            labelLarge = TextStyle(fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp)),
        shapes = Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp)),
        colorScheme=palette.copy(primary=accent,onPrimary=if(accent.luminance()>.4f)Color(0xFF101114) else Color.White,
        outline=if(dark)Color(0xFF696975) else Color(0xFF858590),outlineVariant=if(dark)Color(0xFF303039) else Color(0xFFE0E0E7),
        onBackground=if(dark)Color(0xFFF2F2F5) else Color(0xFF17171D),secondary=accent,secondaryContainer=lerp(background,selected,.23f),onSecondaryContainer=if(dark)Color.White else Color.Black,
        background=background,surface=background,surfaceVariant=lerp(if(dark)Color(0xFF212227) else Color(0xFFE8E8EF),selected,.08f),
        onSurface=if(dark)Color(0xFFF2F2F5) else Color(0xFF17171D),onSurfaceVariant=if(dark)Color(0xFFB6B6C0) else Color(0xFF50505A)),content=content)
}

@Composable
fun SettingsScreen(settings: AppPreferences, prefs: SharedPreferences, library: List<LibraryItem>, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var hex by remember(settings.color) { mutableStateOf("%06X".format(settings.color and 0xFFFFFF)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("À ta façon", fontSize = 28.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Fermer les réglages") }
                } }
                item { Text("Ta couleur", fontSize = 20.sp) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFBCEB81, 0xFF7EC8FF, 0xFFBC9DFF, 0xFFFF9CBD, 0xFFFFB477, 0xFF70E1D5, 0xFFFFDC79).forEach { value ->
                        val color = Color(value)
                        Box(Modifier.size(48.dp).background(color, CircleShape).clickable { prefs.edit().putInt("color", color.toArgb()).apply() }
                            .semantics { contentDescription = "Couleur #${value.toString(16).takeLast(6)}" }, contentAlignment = Alignment.Center) {
                            if (settings.color == color.toArgb()) Icon(Icons.Rounded.Check, null, tint = Color.Black)
                        }
                    }
                } }
                item {
                    val hsv = remember(settings.color) { FloatArray(3).also { android.graphics.Color.colorToHSV(settings.color, it) } }
                    Column {
                        Text("Teinte")
                        Slider(hsv[0], { value -> prefs.edit().putInt("color", android.graphics.Color.HSVToColor(floatArrayOf(value, hsv[1].coerceAtLeast(.15f), hsv[2].coerceAtLeast(.3f)))).apply() }, valueRange = 0f..359f)
                        Text("Intensité")
                        Slider(hsv[1], { value -> prefs.edit().putInt("color", android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], value, hsv[2]))).apply() })
                        Text("Luminosité")
                        Slider(hsv[2], { value -> prefs.edit().putInt("color", android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], value))).apply() }, valueRange = .2f..1f)
                    }
                }
                item { OutlinedTextField(hex, { hex = it.removePrefix("#").take(6) }, label = { Text("Couleur personnalisée · RRVVBB") }, prefix = { Text("#") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                        IconButton(enabled = hex.matches(Regex("[0-9a-fA-F]{6}")), onClick = { prefs.edit().putInt("color", (0xFF000000L or hex.toLong(16)).toInt()).apply() }) {
                            Icon(Icons.Rounded.Check, "Appliquer cette couleur")
                        }
                    }) }
                item { Text("Les tons très sombres sont éclaircis pour garder les commandes lisibles. Le choix est conservé automatiquement.", color = Muted, fontSize = 12.sp) }
                item { HorizontalDivider(); Text("Transitions DJ", fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp)) }
                item {
                    Column {
                        Text(if (settings.mixSeconds == 0) "Fondu désactivé" else "Fondu audio · ${settings.mixSeconds} secondes")
                        Slider(settings.mixSeconds.toFloat(), { prefs.edit().putInt("mixSeconds", it.toInt()).apply() }, valueRange = 0f..60f, steps = 59)
                        Text("Les sons se chevauchent progressivement. Pour les vidéos, l’image passe par un fondu au noir. Les flux en direct passent sans mixage automatique.", color = Muted, fontSize = 13.sp)
                    }
                }
                item { HorizontalDivider(); Text("Bibliothèque", fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp)) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Actualiser au retour dans l’application"); Text("Après avoir autorisé le scan dans Sources.", color = Muted, fontSize = 12.sp) }
                    Switch(settings.autoScan, { prefs.edit().putBoolean("autoScan", it).apply() })
                } }
                item { Text("Diaporama · ${settings.slideshowSeconds} secondes par photo")
                    Slider(settings.slideshowSeconds.toFloat(), { prefs.edit().putInt("slideshowSeconds", it.toInt()).apply() }, valueRange = 3f..15f, steps = 11)
                }
                item { AdvancedSettings(vm, library) }
                item { HorizontalDivider(); Text("Minuterie de sommeil", fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp)) }
                item { Text(if (settings.sleepUntil > SystemClock.elapsedRealtime()) "Minuterie active : la lecture s’arrêtera automatiquement." else "Aucune minuterie active.", color = Muted) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes -> OutlinedButton(onClick = {
                        prefs.edit().putLong("sleepUntil", SystemClock.elapsedRealtime() + minutes * 60_000L).apply()
                    }) { Text("$minutes min") } }
                    TextButton(onClick = { prefs.edit().putLong("sleepUntil", 0).apply() }) { Text("Annuler") }
                } }
            }
        }
    }
}
