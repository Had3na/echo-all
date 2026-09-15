package fr.nacre.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_BACKUP_CHARS = 40_000_000

suspend fun exportBackup(context: Context, uri: Uri, library: List<LibraryItem>) = withContext(Dispatchers.IO) {
    val root=JSONObject().put("format","nacre-1")
    root.put("library",JSONArray().apply { library.forEach { item -> put(JSONObject().apply {
        put("uri",item.uri);put("title",item.title);put("kind",item.kind.name);put("source",item.source);put("favorite",item.favorite)
        put("scanned",item.scanned);put("artist",item.artist);put("album",item.album);put("folder",item.folder);put("duration",item.durationMs);put("added",item.addedAt);put("tagged",item.tagged)
    }) } })
    root.put("settings",JSONObject().apply { context.getSharedPreferences("settings",Context.MODE_PRIVATE).all.forEach { (key,value)->
        if(key !in listOf("sleepUntil","eqStatus")) put(key,if(value is Set<*>) JSONArray(value.toList()) else value)
    } })
    // Same key layout as the preferences of older versions; covers are embedded as JPEG data.
    root.put("studio",StudioStore(context).snapshot().toLegacyJson(Covers::exportValue))
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(root.toString())} ?: error("Destination inaccessible.")
}

suspend fun readBackup(context: Context, uri: Uri): Pair<List<LibraryItem>,JSONObject> = withContext(Dispatchers.IO) {
    val text=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val result=StringBuilder();val block=CharArray(4096)
        while(true){val count=reader.read(block);if(count<0)break;check(result.length+count<=MAX_BACKUP_CHARS){"Sauvegarde trop volumineuse (maximum 40 Mo)."};result.append(block,0,count)}
        result.toString()
    } ?: error("Fichier inaccessible.")
    val root=JSONObject(text);require(root.getString("format")=="nacre-1"){"Format de sauvegarde inconnu."}
    val array=root.getJSONArray("library");require(array.length()<=20_000)
    val items=List(array.length()){i->val j=array.getJSONObject(i);val ref=j.getString("uri");require(Uri.parse(ref).scheme in listOf("https","content"))
        LibraryItem(ref,j.getString("title").take(500),MediaKind.valueOf(j.getString("kind")),j.optString("source","Téléphone"),j.optBoolean("favorite"),j.optBoolean("scanned"),j.optLong("duration"),j.optString("artist"),j.optString("folder"),j.optLong("added"),j.optString("album"),j.optBoolean("tagged")) }
    items to root
}

/** Validates everything first; the returned commit writes settings and studio data. Blocking commit: call it off the main thread. */
fun preparePreferences(context: Context, root: JSONObject): () -> Unit {
    val studioJson=root.optJSONObject("studio") ?: JSONObject()
    val parsed=parseStudio(studioJson.keys().asSequence().associateWith { studioJson.get(it) },strict=true)
    // History is not restored, as before: only playlists, cues, bookmarks, pads and covers.
    val studio=parsed.copy(plays=emptyMap())
    val j=root.optJSONObject("settings") ?: JSONObject()
    val edit=context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit()
    if(j.has("homeTitle")) edit.putString("homeTitle", j.optString("homeTitle").take(60))
    if(j.has("homeOrder")) edit.putString("homeOrder", homeSections(j.optString("homeOrder")).joinToString(","))
    if(j.has("homeCount")) edit.putInt("homeCount", j.optInt("homeCount", 6).coerceIn(3, 20))
    if(j.has("homeCompact")) edit.putBoolean("homeCompact", j.optBoolean("homeCompact"))
    if(j.optString("homeShape") in listOf("card","wide")) edit.putString("homeShape", j.getString("homeShape"))
    j.optJSONArray("homeHidden")?.let { array -> edit.putStringSet("homeHidden", (0 until array.length().coerceAtMost(4)).map { array.optString(it) }.filter { it in homeSections("") }.toSet()) }
    if(j.has("color"))edit.putInt("color",j.getInt("color"))
    for((key,range) in mapOf("mixSeconds" to 0..60,"slideshowSeconds" to 3..15,"minAudioSeconds" to 0..120)) if(j.has(key))edit.putInt(key,j.getInt(key).coerceIn(range))
    for(key in listOf("private","reduceMotion","tempoSync"))if(j.has(key))edit.putBoolean(key,j.optBoolean(key))
    for((key,allowed) in mapOf("theme" to listOf("dark","light","system"),"mixStyle" to MIX_STYLES))if(j.optString(key) in allowed)edit.putString(key,j.getString(key))
    for(i in 0..4)if(j.has("eq$i"))edit.putInt("eq$i",j.getInt("eq$i").coerceIn(-12,12))
    j.optJSONArray("excludedFolders")?.let{arr->edit.putStringSet("excludedFolders",List(arr.length().coerceAtMost(1000)){arr.getString(it)}.toSet())}
    return {
        edit.apply()
        val store=StudioStore(context);val covers=Covers.importValues(context,studio.covers)
        val replaced=store.covers().filter { (key,path)->key in covers && covers[key]!=path }.values
        store.restore(studio.copy(covers=covers));replaced.forEach { Covers.forget(context,it) }
    }
}
