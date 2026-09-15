package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class MediaFeaturesTest {
    private val song = LibraryItem("content://media/audio/1", "Une chanson", MediaKind.MUSIC, favorite = true, scanned = true)
    private val photo = LibraryItem("content://media/images/1", "Une photo", MediaKind.PHOTO, scanned = true)
    private val stream = LibraryItem("https://example.org/song.mp3", "Radio", MediaKind.MUSIC, "Streaming")

    @Test fun scanPreservesFavoritesAndManualEntries() {
        val found = song.copy(favorite = false, title = "Nouveau titre")
        val result = mergeScan(listOf(song, stream), ScanResult(listOf(found), setOf(MediaKind.MUSIC), false), setOf(MediaKind.MUSIC), emptySet())
        assertEquals(2, result.size)
        assertTrue(result.first { it.uri == song.uri }.favorite)
        assertEquals("Nouveau titre", result.first { it.uri == song.uri }.title)
        assertTrue(result.contains(stream))
    }
    @Test fun scanKeepsInformationFoundOnline() {
        val tagged = song.copy(title = "Vrai titre", artist = "Vrai artiste", album = "Vrai album", tagged = true)
        val found = song.copy(favorite = false, title = "track01", artist = "", album = "")
        val result = mergeScan(listOf(tagged), ScanResult(listOf(found), setOf(MediaKind.MUSIC), false), setOf(MediaKind.MUSIC), emptySet()).single()
        assertEquals(tagged, result)
    }
    @Test fun downloadedEntriesAreNotDuplicatedByALaterScan() {
        val downloaded = LibraryItem("content://media/external/audio/media/7", "Titre", MediaKind.MUSIC, "Internet Archive", artist = "Groupe", tagged = true)
        val scanned = downloaded.copy(title = "Groupe - Titre", source = "Téléphone", scanned = true, tagged = false)
        val result = mergeScan(listOf(downloaded), ScanResult(listOf(scanned), setOf(MediaKind.MUSIC), false), setOf(MediaKind.MUSIC), emptySet())
        assertEquals(listOf(downloaded), result)
    }
    @Test fun revokedPermissionHidesScannedMediaButRetainsImports() {
        assertEquals(listOf(stream), mergeScan(listOf(song, photo, stream), ScanResult(emptyList(), emptySet(), false), emptySet(), emptySet()))
    }
    @Test fun failedQueryDoesNotEraseCategory() {
        val result = mergeScan(listOf(song, photo), ScanResult(emptyList(), setOf(MediaKind.PHOTO), true), MediaKind.entries.toSet(), emptySet())
        assertEquals(listOf(song), result)
    }
    @Test fun hiddenMediaStaysHiddenAndDuplicatesCollapse() {
        val result = mergeScan(emptyList(), ScanResult(listOf(song, song, photo), MediaKind.entries.toSet(), false), MediaKind.entries.toSet(), setOf(photo.uri))
        assertEquals(1, result.size)
        assertEquals(song.uri, result.single().uri)
    }
    @Test fun fadesRemainBoundedWithNoGainJump() {
        listOf(-100L, 0L, 500L, 1500L, 3000L, 5000L).forEach { elapsed ->
            val gains = mixGains(elapsed, 3000)
            assertTrue(gains.incoming in 0f..1f)
            assertTrue(gains.outgoing in 0f..1f)
            assertEquals(1f, gains.incoming + gains.outgoing, .0001f)
        }
        assertEquals(MixGains(1f, 0f), mixGains(0, 3000))
        assertEquals(MixGains(0f, 1f), mixGains(3000, 3000))
        assertEquals(MixGains(0f, 1f), mixGains(0, 0))
    }
}
