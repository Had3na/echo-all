package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TorrentModelsTest {
    @Test fun acceptsHexAndBase32AndV2Magnets() {
        for (xt in listOf("urn:btih:" + "a".repeat(40), "urn:btih:" + "A".repeat(32), "urn:btmh:1220" + "f".repeat(64))) {
            val uri = "magnet:?xt=$xt&dn=Mon%20film"
            assertEquals(uri, torrentMagnet(" $uri "))
            assertEquals("Mon film", torrentDisplayName(uri))
        }
    }
    @Test fun rejectsMissingOrMalformedTorrentHashes() {
        for (bad in listOf("https://example.org/test", "magnet:?dn=test", "magnet:?xt=urn:btih:123", "magnet:?xt=urn:btih:" + "0".repeat(32), "magnet:?xt=%XX")) {
            assertTrue(bad, runCatching { torrentMagnet(bad) }.isFailure)
        }
    }
    @Test fun rejectsEscapingPathsOnBothPlatforms() {
        val root = Files.createTempDirectory("torrent-path").toFile()
        try {
            for (path in listOf("../bad", "..\\bad", "/absolute", "C:\\bad", "a/../../bad", "a//b", "a/./b", "bad\u0000file"))
                assertTrue(path, runCatching { torrentChild(root, path) }.isFailure)
            assertEquals(root.resolve("series/episode.mkv").canonicalFile, torrentChild(root, "series/episode.mkv"))
        } finally { root.delete() }
    }
    @Test fun doesNotPretendUnknownFilesAreVideo() {
        assertEquals("video/x-matroska", torrentMime("FILM.MKV"))
        assertEquals("audio/flac", torrentMime("music.flac"))
        assertEquals("application/octet-stream", torrentMime("setup.exe"))
    }
    @Test fun pausedFailedAndCompleteJobsAreNotActive() {
        for (state in TorrentState.entries) assertEquals(state in listOf(TorrentState.QUEUED, TorrentState.METADATA, TorrentState.CHECKING, TorrentState.RUNNING), TorrentJob("id", "title", state = state).active)
    }
}
