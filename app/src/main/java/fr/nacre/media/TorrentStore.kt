package fr.nacre.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object TorrentStore {
    private val jobs = MutableStateFlow<List<TorrentJob>>(emptyList())
    val active = jobs.asStateFlow()
    private var journal: AtomicFile? = null
    private var lastSave = 0L

    @Synchronized fun initialize(context: Context) {
        if (journal != null) return
        val file = AtomicFile(File(context.filesDir, "torrents.json"))
        val list = if (file.baseFile.exists()) {
            require(file.baseFile.length() < 12_000_000) { "Liste de torrents trop volumineuse." }
            val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
            require(array.length() <= 200) { "Trop de téléchargements enregistrés." }
            (0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                val id = UUID.fromString(j.getString("id")).toString()
                val state = TorrentState.valueOf(j.getString("state"))
                TorrentJob(id, j.getString("title"), j.optString("magnet"),
                    if (state in listOf(TorrentState.DONE, TorrentState.FAILED, TorrentState.PAUSED)) state else TorrentState.PAUSED,
                    j.optLong("downloaded"), j.optLong("total"), error = j.optString("error"),
                    files = j.optJSONArray("files")?.let { a -> List(a.length().coerceAtMost(10_000)) { a.getString(it) } }.orEmpty())
            }
        } else emptyList()
        journal = file; jobs.value = list
        save()
    }
    @Synchronized private fun save() {
        val file = journal ?: return
        val array = JSONArray().apply { jobs.value.forEach { job -> put(JSONObject().apply {
            put("id", job.id); put("title", job.title); put("magnet", job.magnet); put("state", job.state.name)
            put("downloaded", job.downloaded); put("total", job.total); put("error", job.error); put("files", JSONArray(job.files))
        }) } }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        lastSave = System.nanoTime()
    }
    @Synchronized fun add(job: TorrentJob) {
        require(jobs.value.size < 200) { "Maximum 200 torrents enregistrés." }
        require(job.magnet.isBlank() || jobs.value.none { it.magnet == job.magnet }) { "Ce magnet est déjà dans la liste." }
        val old = jobs.value
        require(old.none { it.id == job.id }) { "Ce torrent est déjà dans la liste." }
        jobs.value = old + job
        try { save() } catch (e: Exception) { jobs.value = old; throw e }
    }
    @Synchronized fun change(id: String, transform: (TorrentJob) -> TorrentJob) {
        val old = jobs.value
        jobs.value = old.map { if (it.id == id) transform(it) else it }
        try { save() } catch (e: Exception) { jobs.value = old; throw e }
    }
    @Synchronized fun progress(job: TorrentJob) {
        val previous = jobs.value.firstOrNull { it.id == job.id && it.state in listOf(TorrentState.METADATA, TorrentState.CHECKING, TorrentState.RUNNING) } ?: return
        jobs.value = jobs.value.map { if (it.id == job.id) job else it }
        if (previous.state != job.state || System.nanoTime() - lastSave > 5_000_000_000L) save()
    }
    @Synchronized fun pauseAll() {
        jobs.value = jobs.value.map { if (it.active) it.copy(state = TorrentState.PAUSED, speed = 0) else it }
        save()
    }
    fun metadata(context: Context, id: String): File = File(context.filesDir, "torrent-metadata/${UUID.fromString(id)}.torrent")
    private fun internalFolder(context: Context, id: String) = File(context.filesDir, "downloads/torrents/${UUID.fromString(id)}")
    private fun legacyFolder(context: Context, id: String): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.let { File(it, "torrents/${UUID.fromString(id)}") }
    fun folder(context: Context, id: String): File {
        val target = internalFolder(context, id)
        return if (target.isDirectory) target else legacyFolder(context, id)?.takeIf { it.isDirectory } ?: target
    }
    fun prepareFolder(context: Context, id: String): File = prepareInternalTorrentFolder(internalFolder(context, id), legacyFolder(context, id))
    fun fileUri(context: Context, job: TorrentJob, relative: String): Uri {
        require(job.state == TorrentState.DONE && relative in job.files)
        val file = torrentChild(folder(context, job.id), relative)
        require(file.isFile) { "Fichier introuvable." }
        return FileProvider.getUriForFile(context, context.packageName + ".torrents", file)
    }
    @Synchronized fun forget(id: String) {
        require(jobs.value.none { it.id == id && it.active }) { "Mets le torrent en pause avant de le retirer." }
        jobs.value = jobs.value.filterNot { it.id == id }; save()
    }
}
