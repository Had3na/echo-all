package fr.nacre.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.FileStorage
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.error_code
import java.io.File
import java.io.IOException

internal fun validateTorrentFiles(info: TorrentInfo, folder: File): List<String> {
    require(info.isValid && info.numFiles() in 1..10_000) { "Torrent invalide ou trop de fichiers (maximum 10 000)." }
    val storage = info.files()
    return (0 until info.numFiles()).mapNotNull { index ->
        require(!storage.fileFlags(index).and_(FileStorage.FLAG_SYMLINK).non_zero()) { "Les liens symboliques ne sont pas acceptés dans un torrent." }
        if (storage.fileFlags(index).and_(FileStorage.FLAG_PAD_FILE).non_zero()) return@mapNotNull null
        storage.filePath(index).also { torrentChild(folder, it) }
    }
}

/** One session per active job: stopping it flushes disk buffers before reporting completion. */
internal suspend fun runTorrent(job: TorrentJob, metadata: File, folder: File, settingsOverride: ((SettingsPack) -> Unit)? = null, onProgress: (TorrentJob) -> Unit): List<String> = withContext(Dispatchers.IO) {
    val session = SessionManager()
    var handle: TorrentHandle? = null
    try {
        check(folder.isDirectory || folder.mkdirs()) { "Dossier de téléchargement inaccessible." }
        val settings = SettingsPack().connectionsLimit(80).activeDownloads(1)
        settings.setMaxMetadataSize(MAX_TORRENT_BYTES)
        settings.setDhtBootstrapNodes("router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881")
        settingsOverride?.invoke(settings)
        session.start(SessionParams(settings))
        if (!metadata.exists()) {
            onProgress(job.copy(state = TorrentState.METADATA))
            val bytes = runInterruptible {
                session.fetchMagnet(torrentMagnet(job.magnet), 90, File(folder.parentFile, "metadata-${job.id}").apply { mkdirs() })
            }
            currentCoroutineContext().ensureActive()
            if (bytes == null) throw IOException("Métadonnées non reçues après 90 secondes. Les pairs ou le réseau peuvent être indisponibles ; réessaie ou importe un fichier .torrent.")
            require(bytes.size <= MAX_TORRENT_BYTES) { "Fichier torrent trop volumineux." }
            metadata.parentFile?.mkdirs()
            val staging = File(metadata.parentFile, metadata.name + ".tmp")
            staging.writeBytes(bytes)
            check(staging.renameTo(metadata)) { "Impossible de conserver les métadonnées." }
        }
        require(metadata.length() in 1..MAX_TORRENT_BYTES.toLong()) { "Fichier torrent invalide ou trop volumineux." }
        // Loading only TorrentInfo loses trackers/web seeds in libtorrent 2.1. Keep the full params.
        val parseError = error_code()
        val params = add_torrent_params.load_torrent_file(metadata.absolutePath, parseError)
        if (parseError.value() != 0) throw IOException("Fichier torrent invalide : ${parseError.message()}")
        val info = TorrentInfo(params.ti_ptr() ?: throw IOException("Métadonnées torrent absentes."))
        val files = validateTorrentFiles(info, folder)
        var current = job.copy(title = info.name().take(200), total = info.totalSize(), state = TorrentState.CHECKING, files = files)
        onProgress(current)
        if (folder.usableSpace < 16L * 1024 * 1024) throw IOException("Espace de stockage insuffisant.")
        if (job.magnet.isNotBlank()) {
            val magnetParams = AddTorrentParams.parseMagnetUri(torrentMagnet(job.magnet))
            val full = AddTorrentParams(params)
            full.setTrackers((full.trackers + magnetParams.trackers).distinct())
            full.setUrlSeeds((full.urlSeeds + magnetParams.urlSeeds).distinct())
            params.setPeers(magnetParams.swig().peers)
        }
        params.setSave_path(folder.absolutePath)
        params.setFlags(params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv()))
        val addError = error_code()
        handle = TorrentHandle(session.swig().add_torrent(params, addError))
        if (addError.value() != 0 || !handle!!.isValid) throw IOException("Impossible de démarrer le torrent : ${addError.message()}")
        val torrent = handle!!
        torrent.unsetFlags(TorrentFlags.AUTO_MANAGED)
        torrent.resume()
        while (true) {
            currentCoroutineContext().ensureActive()
            val status = torrent.status(true)
            if (status.errorCode().isError) throw IOException("Torrent : ${status.errorCode().message}")
            current = current.copy(downloaded = status.totalWantedDone(), total = status.totalWanted(),
                speed = status.downloadRate().toLong(), peers = status.numPeers(),
                state = if (status.state().toString().contains("CHECK", true)) TorrentState.CHECKING else TorrentState.RUNNING)
            onProgress(current)
            if (status.isSeeding || status.isFinished) {
                torrent.unsetFlags(TorrentFlags.AUTO_MANAGED)
                torrent.pause()
                break
            }
            delay(1000)
        }
        files
    } finally {
        // Do not leave a native session sharing after pause, cancellation, timeout or completion.
        withContext(NonCancellable + Dispatchers.IO) { session.stop() }
    }
}
