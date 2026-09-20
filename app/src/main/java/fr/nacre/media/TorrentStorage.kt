package fr.nacre.media

import java.io.File
import java.nio.file.Files

/** Copy old external data before resuming internally. Keep the original until the user exports it. */
internal fun prepareInternalTorrentFolder(target: File, legacy: File?): File {
    if (target.isDirectory) return target
    if (legacy == null || !legacy.isDirectory) {
        check(target.mkdirs() || target.isDirectory) { "Dossier torrent interne inaccessible." }
        return target
    }
    val sourceRoot = legacy.canonicalFile
    val files = legacy.walkTopDown().onEnter {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(sourceRoot.toPath())) { "Dossier torrent non valide." }
        true
    }.filter { it.isFile }.toList()
    files.forEach { require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.toPath().startsWith(sourceRoot.toPath())) }
    target.parentFile.mkdirs()
    val size = files.sumOf { it.length() }
    require(target.parentFile.usableSpace > size + 16L * 1024 * 1024) { "Espace insuffisant pour reprendre les fichiers dans le stockage interne. Les anciens fichiers sont conservés." }
    val staging = File(target.parentFile, target.name + ".migration")
    check(staging.mkdirs() || staging.isDirectory)
    files.forEach { source ->
        val destination = torrentChild(staging, source.relativeTo(legacy).path)
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
    check(staging.renameTo(target)) { "Impossible de terminer la migration du torrent." }
    return target
}
