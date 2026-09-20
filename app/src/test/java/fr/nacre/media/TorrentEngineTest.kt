package fr.nacre.media

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.concurrent.thread

class TorrentEngineTest {
    companion object {
        @org.junit.BeforeClass @JvmStatic fun loadNativeTestLibrary() {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
            val binary = Files.createTempDirectory("echo-libtorrent-test").resolve("libtorrent4j.dll").toFile()
            TorrentEngineTest::class.java.getResourceAsStream("/lib/x86_64/libtorrent4j.dll")!!.use { input -> binary.outputStream().use { input.copyTo(it) } }
            binary.deleteOnExit(); binary.parentFile?.deleteOnExit()
            System.setProperty("libtorrent4j.jni.path", binary.absolutePath)
        }
    }
    private fun offline(settings: SettingsPack) {
        settings.setDhtBootstrapNodes("")
        settings.listenInterfaces("127.0.0.1:0")
        for (flag in listOf(settings_pack.bool_types.enable_dht, settings_pack.bool_types.enable_lsd,
            settings_pack.bool_types.enable_upnp, settings_pack.bool_types.enable_natpmp)) settings.setBoolean(flag.swigValue(), false)
    }
    @Test fun nativeEngineRechecksExistingDataOnResume() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val root = Files.createTempDirectory("echo-torrent-resume").toFile()
        try {
            val destination = File(root, "data").apply { mkdirs() }
            val source = File(destination, "fixture.txt").apply { writeBytes(ByteArray(65536) { (it % 251).toByte() }) }
            val meta = File(root, "fixture.torrent").apply { writeBytes(TorrentBuilder().path(source).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()) }
            val updates = mutableListOf<TorrentJob>()
            val files = runBlocking { withTimeout(20000) {
                runTorrent(TorrentJob(UUID.randomUUID().toString(), "Fixture"), meta, destination, ::offline) { updates += it }
            } }
            assertEquals(listOf("fixture.txt"), files)
            assertEquals(source.length(), updates.last().downloaded)
            assertTrue(updates.any { it.state == TorrentState.CHECKING })
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeEngineDownloadsAndVerifiesBytesFromLocalWebSeed() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val root = Files.createTempDirectory("echo-torrent-transfer").toFile()
        val payload = ByteArray(131072) { ((it * 17 + 23) % 251).toByte() }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty(); println("WEBSEED REQUEST: $first")
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; headers += line }
                    val range = headers.firstOrNull { h -> h.startsWith("Range:", true) }?.substringAfter("bytes=")
                    val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    val end = (range?.substringAfter('-')?.toIntOrNull() ?: payload.lastIndex).coerceAtMost(payload.lastIndex)
                    val status = if (range == null) "200 OK" else "206 Partial Content"
                    val contentRange = if (range == null) "" else "Content-Range: bytes $start-$end/${payload.size}\r\n"
                    val header = "HTTP/1.1 $status\r\nContent-Length: ${end - start + 1}\r\n${contentRange}Accept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                    runCatching { it.getOutputStream().apply { write(header.toByteArray()); if (!first.startsWith("HEAD")) write(payload, start, end - start + 1); flush() } }
                }
            }
        }
        try {
            val source = File(root, "fixture.bin").apply { writeBytes(payload) }
            val meta = File(root, "fixture.torrent").apply {
                writeBytes(TorrentBuilder().path(source).flags(TorrentBuilder.V1_ONLY)
                    .addUrlSeed("http://127.0.0.1:${server.localPort}/fixture.bin").generate().entry().bencode())
            }
            val destination = File(root, "download")
            val updates = mutableListOf<TorrentJob>()
            runBlocking { withTimeout(40000) {
                runTorrent(TorrentJob(UUID.randomUUID().toString(), "Local transfer"), meta, destination, {
                    offline(it)
                    // Allow only this loopback test seed. Production keeps the native protection enabled.
                    it.setBoolean(settings_pack.bool_types.ssrf_mitigation.swigValue(), false)
                }) { updates += it; println("TRANSFER ${it.state} ${it.downloaded}/${it.total} peers=${it.peers}") }
            } }
            assertArrayEquals(payload, File(destination, "fixture.bin").readBytes())
            assertEquals(payload.size.toLong(), updates.last().downloaded)
        } finally { server.close(); serving.join(2000); root.deleteRecursively() }
    }
    @Test fun magnetFetchesMetadataAndDownloadsFromExplicitLocalPeer() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val root = Files.createTempDirectory("echo-torrent-magnet").toFile()
        val seeder = org.libtorrent4j.SessionManager()
        try {
            val seedFolder = File(root, "seed").apply { mkdirs() }
            val payload = ByteArray(262144) { ((it * 31 + 7) % 251).toByte() }
            val source = File(seedFolder, "magnet-fixture.bin").apply { writeBytes(payload) }
            val meta = File(root, "seed.torrent").apply { writeBytes(TorrentBuilder().path(source).flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()) }
            val seedSettings = SettingsPack(); offline(seedSettings)
            seeder.start(org.libtorrent4j.SessionParams(seedSettings))
            val ec = org.libtorrent4j.swig.error_code()
            val params = org.libtorrent4j.swig.add_torrent_params.load_torrent_file(meta.absolutePath, ec)
            params.setSave_path(seedFolder.absolutePath)
            params.setFlags(params.flags.and_(org.libtorrent4j.TorrentFlags.AUTO_MANAGED.inv()).and_(org.libtorrent4j.TorrentFlags.PAUSED.inv()))
            val handle = org.libtorrent4j.TorrentHandle(seeder.swig().add_torrent(params, ec))
            assertEquals(0, ec.value())
            runBlocking { withTimeout(10000) { while (!handle.status(true).isSeeding || seeder.swig().listen_port() == 0) delay(100) } }
            val hash = org.libtorrent4j.TorrentInfo(meta).infoHashes().best.toHex()
            val magnet = "magnet:?xt=urn:btih:$hash&x.pe=127.0.0.1:${seeder.swig().listen_port()}"
            val downloadedMetadata = File(root, "received.torrent")
            val destination = File(root, "download")
            runBlocking { withTimeout(35000) {
                runTorrent(TorrentJob(UUID.randomUUID().toString(), "Magnet test", magnet), downloadedMetadata, destination, ::offline) { }
            } }
            assertTrue(downloadedMetadata.isFile)
            assertArrayEquals(payload, File(destination, source.name).readBytes())
        } finally { seeder.stop(); root.deleteRecursively() }
    }
}
