package fr.nacre.media
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
class TorrentStorageTest {
 @Test fun migratesExistingBytesAndKeepsOriginal() {
  val root = Files.createTempDirectory("torrent-storage").toFile()
  try {
   val legacy = File(root,"old").apply { mkdirs() }
   File(legacy,"film.mp4").writeBytes(byteArrayOf(1,2,3))
   val target = File(root,"internal/job")
   assertEquals(target, prepareInternalTorrentFolder(target,legacy))
   assertArrayEquals(byteArrayOf(1,2,3),File(target,"film.mp4").readBytes())
   assertTrue(File(legacy,"film.mp4").exists())
   File(target,"film.mp4").appendBytes(byteArrayOf(4))
   prepareInternalTorrentFolder(target,legacy)
   assertEquals(4L,File(target,"film.mp4").length())
  } finally { root.deleteRecursively() }
 }
 @Test fun newTorrentUsesInternalDirectory() {
  val root=Files.createTempDirectory("torrent-storage").toFile()
  try { assertTrue(prepareInternalTorrentFolder(File(root,"job"),null).isDirectory) }
  finally {root.deleteRecursively()}
 }
}
