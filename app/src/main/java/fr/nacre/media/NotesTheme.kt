package fr.nacre.media

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

fun notesTheme(context: Context): JSONObject {
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE).snapshot()
    val dark = when(settings.theme) { "light" -> false; "system" -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES; else -> true }
    val selected = Color(settings.color)
    val accent = if(dark) { if(selected.luminance()<.18f) lerp(selected,Color.White,.48f) else selected } else { if(selected.luminance()>.3f) lerp(selected,Color.Black,.45f) else selected }
    fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
    return JSONObject().put("dark",dark).put("accent",hex(accent)).put("selected",hex(selected))
        .put("bg",hex(if(dark) lerp(Color(0xFF101114),selected,.035f) else lerp(Color(0xFFFAFAFC),selected,.025f)))
        .put("panel",hex(lerp(if(dark) Color(0xFF212227) else Color(0xFFE8E8EF),selected,.08f)))
        .put("fg",if(dark) "#F2F2F5" else "#17171D").put("muted",if(dark) "#B6B6C0" else "#50505A")
        .put("line",if(dark) "#303039" else "#E0E0E7").put("onAccent",if(accent.luminance()>.4f) "#101114" else "#FFFFFF")
        .put("reduceMotion",settings.reduceMotion)
}
