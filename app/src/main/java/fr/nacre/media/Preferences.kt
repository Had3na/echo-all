package fr.nacre.media

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

data class AppPreferences(val color: Int = 0xFFE879F9.toInt(), val mixSeconds: Int = 3,
    val autoScan: Boolean = false, val slideshowSeconds: Int = 5, val sleepUntil: Long = 0, val theme: String = "dark", val reduceMotion: Boolean = false)

fun SharedPreferences.snapshot() = AppPreferences(getInt("color", 0xFFE879F9.toInt()),
    getInt("mixSeconds", 3), getBoolean("autoScan", false), getInt("slideshowSeconds", 5), getLong("sleepUntil", 0), getString("theme", "dark").orEmpty(), getBoolean("reduceMotion", false))

@Composable
fun rememberPreferences(): Pair<AppPreferences, SharedPreferences> {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(preferences.snapshot()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ -> state = prefs.snapshot() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state to preferences
}
