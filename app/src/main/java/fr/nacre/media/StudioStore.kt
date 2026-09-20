package fr.nacre.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class TrackTools(val bpm: Float = 0f, val confidence: Float = 0f, val cueIn: Long = 0, val cueOut: Long = 0,
    val loopIn: Long = 0, val loopOut: Long = 0, val loop: Boolean = false, val wave: List<Float> = emptyList(),
    /** Where the beat grid starts, in milliseconds. 0 when the tempo was never measured. */
    val beatMs: Long = 0)
data class SavedList(val id: String, val name: String, val uris: List<String>, val style: String = "smooth", val seconds: Int = 5, val shuffle: Boolean = false)

/** In-process change notifications ("track:<uri>", "lists", "cover:<key>", "plays"), delivered on the main thread. */
object StudioEvents {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    fun listen(listener: (String) -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    fun emit(key: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it(key) } else main.post { listeners.forEach { it(key) } }
    }
}

private const val DOWNLOADS_TABLE = "CREATE TABLE downloads(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, cover_url TEXT NOT NULL, duration INTEGER NOT NULL)"

private const val LOUDNESS_TABLE = "CREATE TABLE loudness(uri TEXT PRIMARY KEY, lufs REAL NOT NULL, seconds REAL NOT NULL)"

private class StudioDatabase(private val context: Context) : SQLiteOpenHelper(context, "studio.db", null, 5) {
    var importedLegacy = false; private set
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE tracks(uri TEXT PRIMARY KEY, bpm REAL NOT NULL, confidence REAL NOT NULL, cue_in INTEGER NOT NULL, cue_out INTEGER NOT NULL, loop_in INTEGER NOT NULL, loop_out INTEGER NOT NULL, looping INTEGER NOT NULL, wave TEXT NOT NULL, beat INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE plays(uri TEXT PRIMARY KEY, count INTEGER NOT NULL, last INTEGER NOT NULL)",
            "CREATE TABLE marks(uri TEXT NOT NULL, time INTEGER NOT NULL, PRIMARY KEY(uri, time))",
            "CREATE TABLE pads(uri TEXT NOT NULL, pad INTEGER NOT NULL, time INTEGER NOT NULL, PRIMARY KEY(uri, pad))",
            "CREATE TABLE lists(id TEXT PRIMARY KEY, position INTEGER NOT NULL, name TEXT NOT NULL, style TEXT NOT NULL, seconds INTEGER NOT NULL, shuffle INTEGER NOT NULL)",
            "CREATE TABLE list_items(list_id TEXT NOT NULL, position INTEGER NOT NULL, uri TEXT NOT NULL, PRIMARY KEY(list_id, position))",
            "CREATE TABLE covers(key TEXT PRIMARY KEY, path TEXT NOT NULL)",
            DOWNLOADS_TABLE,
            "CREATE TABLE companion(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            LOUDNESS_TABLE
        ).forEach(db::execSQL)
        // Versions up to 0.6 kept everything in the "studio" preferences: carry it over in the same transaction.
        val legacy = context.getSharedPreferences("studio", Context.MODE_PRIVATE).all
        if (legacy.isNotEmpty()) { writeSnapshot(db, parseStudio(legacy, strict = false)); importedLegacy = true }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(DOWNLOADS_TABLE)
        if (oldVersion < 3) db.execSQL(LOUDNESS_TABLE)
        if (oldVersion < 4) db.execSQL("CREATE TABLE companion(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (oldVersion < 5) db.execSQL("ALTER TABLE tracks ADD COLUMN beat INTEGER NOT NULL DEFAULT 0")
    }

    companion object {
        @Volatile private var database: SQLiteDatabase? = null
        fun open(context: Context): SQLiteDatabase = database ?: synchronized(this) {
            database ?: run {
                val app = context.applicationContext
                val helper = StudioDatabase(app)
                val db = helper.writableDatabase
                // Only drop the old preferences once the migration transaction has been committed.
                if (helper.importedLegacy) app.deleteSharedPreferences("studio")
                db.also { database = it }
            }
        }
    }
}

private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try { block(); setTransactionSuccessful() } finally { endTransaction() }
}

private fun trackValues(uri: String, data: TrackTools) = ContentValues().apply {
    put("uri", uri); put("bpm", data.bpm); put("confidence", data.confidence); put("cue_in", data.cueIn); put("cue_out", data.cueOut)
    put("loop_in", data.loopIn); put("loop_out", data.loopOut); put("looping", if (data.loop) 1 else 0); put("wave", encodeWave(data.wave)); put("beat", data.beatMs)
}

private fun writeList(db: SQLiteDatabase, list: SavedList) {
    val value = list.normalized()
    val position = db.rawQuery("SELECT position FROM lists WHERE id = ?", arrayOf(value.id)).use { if (it.moveToFirst()) it.getLong(0) else null }
        ?: db.rawQuery("SELECT COALESCE(MAX(position) + 1, 0) FROM lists", null).use { it.moveToFirst(); it.getLong(0) }
    db.insertWithOnConflict("lists", null, ContentValues().apply {
        put("id", value.id); put("position", position); put("name", value.name); put("style", value.style); put("seconds", value.seconds); put("shuffle", if (value.shuffle) 1 else 0)
    }, SQLiteDatabase.CONFLICT_REPLACE)
    val stored = db.rawQuery("SELECT uri FROM list_items WHERE list_id = ? ORDER BY position", arrayOf(value.id)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    if (stored == value.uris) return
    db.delete("list_items", "list_id = ?", arrayOf(value.id))
    val insert = db.compileStatement("INSERT INTO list_items(list_id, position, uri) VALUES(?, ?, ?)")
    value.uris.forEachIndexed { i, uri -> insert.bindString(1, value.id); insert.bindLong(2, i.toLong()); insert.bindString(3, uri); insert.executeInsert(); insert.clearBindings() }
}

private fun writeSnapshot(db: SQLiteDatabase, data: StudioSnapshot) = db.transaction {
    data.lists.forEach { writeList(this, it) }
    data.tracks.forEach { (uri, tools) -> insertWithOnConflict("tracks", null, trackValues(uri, tools), SQLiteDatabase.CONFLICT_REPLACE) }
    data.marks.forEach { (uri, times) -> times.forEach { time -> insertWithOnConflict("marks", null, ContentValues().apply { put("uri", uri); put("time", time) }, SQLiteDatabase.CONFLICT_IGNORE) } }
    data.pads.forEach { (slot, time) -> insertWithOnConflict("pads", null, ContentValues().apply { put("uri", slot.uri); put("pad", slot.pad); put("time", time) }, SQLiteDatabase.CONFLICT_REPLACE) }
    data.plays.forEach { (uri, play) -> insertWithOnConflict("plays", null, ContentValues().apply { put("uri", uri); put("count", play.count); put("last", play.last) }, SQLiteDatabase.CONFLICT_REPLACE) }
    data.covers.forEach { (key, path) -> insertWithOnConflict("covers", null, ContentValues().apply { put("key", key); put("path", path) }, SQLiteDatabase.CONFLICT_REPLACE) }
}

/** Studio data (BPM, cues, playlists, history, pads, covers) in a SQLite database: reads are per row, not one big file. */
class StudioStore(context: Context) {
    private val app = context.applicationContext
    private val db get() = StudioDatabase.open(app)

    fun cached(key: String): String? = db.rawQuery("SELECT value FROM companion WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun cache(key: String, value: String) {
        db.insertWithOnConflict("companion", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        StudioEvents.emit("companion:$key")
    }
    fun cacheAll(prefix: String): Map<String, String> = db.rawQuery("SELECT key,value FROM companion WHERE key LIKE ?", arrayOf("$prefix%")).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0).removePrefix(prefix), c.getString(1)) } }
    fun setCoverIfMissing(key: String, path: String): Boolean {
        var added = false
        db.transaction {
            if(cached("autoBlocked:$key") != "true") {
                added = insertWithOnConflict("covers", null, ContentValues().apply { put("key", key); put("path", path) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if(added) insertWithOnConflict("companion", null, ContentValues().apply { put("key", "autoCover:$key"); put("value", path) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        if (added) StudioEvents.emit("cover:$key")
        return added
    }

    fun track(uri: String): TrackTools = db.rawQuery("SELECT bpm, confidence, cue_in, cue_out, loop_in, loop_out, looping, wave, beat FROM tracks WHERE uri = ?", arrayOf(uri)).use { c ->
        if (!c.moveToFirst()) TrackTools()
        else TrackTools(c.getFloat(0), c.getFloat(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getInt(6) != 0, decodeWave(c.getString(7)), c.getLong(8))
    }
    fun saveTrack(uri: String, data: TrackTools) {
        require(data.cueIn >= 0 && (data.cueOut == 0L || data.cueOut > data.cueIn))
        db.insertWithOnConflict("tracks", null, trackValues(uri, data), SQLiteDatabase.CONFLICT_REPLACE)
        StudioEvents.emit("track:$uri")
    }

    fun lists(): List<SavedList> {
        val items = HashMap<String, MutableList<String>>()
        db.rawQuery("SELECT list_id, uri FROM list_items ORDER BY list_id, position", null).use { c -> while (c.moveToNext()) items.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1) }
        return db.rawQuery("SELECT id, name, style, seconds, shuffle FROM lists ORDER BY position", null).use { c ->
            buildList { while (c.moveToNext()) add(SavedList(c.getString(0), c.getString(1), items[c.getString(0)].orEmpty(), c.getString(2), c.getInt(3), c.getInt(4) != 0)) }
        }
    }
    fun saveList(list: SavedList) { db.transaction { writeList(this, list) }; StudioEvents.emit("lists") }
    fun newList(name: String, uris: List<String>) = SavedList(UUID.randomUUID().toString(), name.trim().ifBlank { "Ma sélection" }, uris.distinct())
    fun deleteList(id: String) {
        db.transaction { delete("list_items", "list_id = ?", arrayOf(id)); delete("lists", "id = ?", arrayOf(id)) }
        StudioEvents.emit("lists")
    }

    fun markPlayed(uri: String) {
        val now = System.currentTimeMillis()
        db.transaction {
            val updated = compileStatement("UPDATE plays SET count = count + 1, last = ? WHERE uri = ?").apply { bindLong(1, now); bindString(2, uri) }.executeUpdateDelete()
            if (updated == 0) insert("plays", null, ContentValues().apply { put("uri", uri); put("count", 1); put("last", now) })
        }
        StudioEvents.emit("plays")
    }
    fun historyTime(uri: String) = db.rawQuery("SELECT last FROM plays WHERE uri = ?", arrayOf(uri)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    fun count(uri: String) = db.rawQuery("SELECT count FROM plays WHERE uri = ?", arrayOf(uri)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun counts(): Map<String, Int> = db.rawQuery("SELECT uri, count FROM plays", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
    fun clearHistory() { db.delete("plays", null, null); StudioEvents.emit("plays") }

    fun bookmarks(uri: String): List<Long> = db.rawQuery("SELECT time FROM marks WHERE uri = ? ORDER BY time", arrayOf(uri)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    fun bookmark(uri: String, time: Long) { db.insertWithOnConflict("marks", null, ContentValues().apply { put("uri", uri); put("time", time) }, SQLiteDatabase.CONFLICT_IGNORE) }

    fun pad(uri: String, pad: Int): Long = db.rawQuery("SELECT time FROM pads WHERE uri = ? AND pad = ?", arrayOf(uri, pad.toString())).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    fun setPad(uri: String, pad: Int, time: Long) { db.insertWithOnConflict("pads", null, ContentValues().apply { put("uri", uri); put("pad", pad); put("time", time) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun clearPad(uri: String, pad: Int) { db.delete("pads", "uri = ? AND pad = ?", arrayOf(uri, pad.toString())) }

    fun cover(key: String): String? = db.rawQuery("SELECT path FROM covers WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun covers(): Map<String, String> = db.rawQuery("SELECT key, path FROM covers", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    /** Returns the path it replaced so the caller can delete the old file. */
    fun setCover(key: String, path: String): String? {
        val previous = cover(key)
        db.insertWithOnConflict("covers", null, ContentValues().apply { put("key", key); put("path", path) }, SQLiteDatabase.CONFLICT_REPLACE)
        StudioEvents.emit("cover:$key")
        return previous
    }
    fun removeCover(key: String): String? {
        val previous = cover(key)
        db.delete("covers", "key = ?", arrayOf(key))
        StudioEvents.emit("cover:$key")
        return previous
    }

    /** Measured integrated loudness and how many seconds it is based on. */
    fun loudness(uri: String): Pair<Double, Double>? = db.rawQuery("SELECT lufs, seconds FROM loudness WHERE uri = ?", arrayOf(uri)).use { if (it.moveToFirst()) it.getDouble(0) to it.getDouble(1) else null }
    fun saveLoudness(uri: String, lufs: Double, seconds: Double) {
        db.insertWithOnConflict("loudness", null, ContentValues().apply { put("uri", uri); put("lufs", lufs); put("seconds", seconds) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addDownload(download: PendingDownload) {
        db.insertWithOnConflict("downloads", null, ContentValues().apply {
            put("id", download.id); put("kind", download.kind.name); put("title", download.title); put("artist", download.artist)
            put("album", download.album); put("cover_url", download.coverUrl); put("duration", download.durationMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun downloads(): List<PendingDownload> = db.rawQuery("SELECT id, kind, title, artist, album, cover_url, duration FROM downloads ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(PendingDownload(c.getLong(0), runCatching { MediaKind.valueOf(c.getString(1)) }.getOrDefault(MediaKind.MUSIC), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6))) }
    }
    fun removeDownload(id: Long) { db.delete("downloads", "id = ?", arrayOf(id.toString())) }

    fun snapshot(): StudioSnapshot {
        val tracks = db.rawQuery("SELECT uri FROM tracks", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.associateWith { track(it) }
        val marks = HashMap<String, MutableList<Long>>()
        db.rawQuery("SELECT uri, time FROM marks ORDER BY uri, time", null).use { c -> while (c.moveToNext()) marks.getOrPut(c.getString(0)) { mutableListOf() } += c.getLong(1) }
        val pads = db.rawQuery("SELECT uri, pad, time FROM pads", null).use { c -> buildMap { while (c.moveToNext()) put(PadSlot(c.getString(0), c.getInt(1)), c.getLong(2)) } }
        val plays = db.rawQuery("SELECT uri, count, last FROM plays", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), PlayStat(c.getInt(1), c.getLong(2))) } }
        return StudioSnapshot(lists(), tracks, marks, pads, plays, covers())
    }
    /** Writes restored data in one transaction; entries with the same identifier are replaced. */
    fun restore(data: StudioSnapshot) {
        writeSnapshot(db, data)
        StudioEvents.emit("lists"); StudioEvents.emit("plays")
        data.tracks.keys.forEach { StudioEvents.emit("track:$it") }
        data.covers.keys.forEach { StudioEvents.emit("cover:$it") }
    }
}

fun LibraryItem.playable(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist.ifBlank { source })
        .setAlbumTitle(album).setExtras(Bundle().apply { putBoolean("video", kind == MediaKind.VIDEO); putBoolean("tagged", tagged); putBoolean("autoMetadataBlocked", autoMetadataBlocked) }).build()).build()
