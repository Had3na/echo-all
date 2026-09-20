package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class LibraryFeaturesTest {
    private val original = LibraryItem("content://music/1", "Original", MediaKind.MUSIC, artist = "Artist", album = "Album", scanned = true)
    private val correction = """{"title":"Corrected","artist":"Correct Artist","album":"Correct Album"}"""

    @Test fun albumsWithTheSameNameKeepTheirArtistQueuesSeparate() {
        val groups = musicGroups(listOf(original, original.copy(uri="2", artist="Other")), false)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.tracks.size == 1 })
        assertEquals(2, groups.map { it.key }.distinct().size)
    }
    @Test fun artistsAreNormalizedAndRadioAndVideosAreExcluded() {
        val groups = musicGroups(listOf(original, original.copy(uri="2", artist="ARTIST"), original.copy(uri="3", source="Radio"), original.copy(uri="4", kind=MediaKind.VIDEO)), true)
        assertEquals(1, groups.size)
        assertEquals(setOf(original.uri, "2"), groups.single().tracks.map { it.uri }.toSet())
    }
    @Test fun resumeKeepsUnfinishedVideosButRestartsNearTheEnd() {
        assertEquals(45000L, videoResumePosition(45000, 120000))
        assertEquals(0L, videoResumePosition(119000, 120000))
        assertEquals(0L, videoResumePosition(200000, 120000))
        assertEquals(0L, videoResumePosition(500, 120000))
        assertEquals(0L, videoResumePosition(45000, 0))
    }
    @Test fun longVideosOnlyLoseTheFinalTenSeconds() {
        assertEquals(3500000L, videoResumePosition(3500000, 3600000))
        assertEquals(0L, videoResumePosition(3590000, 3600000))
        assertEquals("1:01:01", mediaClock(3661000))
        assertEquals("0:00", mediaClock(-1))
    }
    @Test fun undoRestoresOnlyMetadataAndPreservesSubsequentUserChanges() {
        val corrected = applyAutomaticTags(original, correction).copy(favorite=true, folder="Moved", videoCategory="Personal")
        val restored = undoAutomaticTags(corrected)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.album, restored.album)
        assertFalse(restored.tagged)
        assertTrue(restored.favorite)
        assertEquals("Moved", restored.folder)
        assertEquals("Personal", restored.videoCategory)
        assertTrue(restored.autoMetadataBlocked)
        assertEquals("", restored.metadataUndo)
        assertEquals(restored, applyAutomaticTags(restored, correction))
    }
    @Test fun manualMetadataAndLegacyItemsAreNotOverwritten() {
        val manual = original.copy(tagged=true)
        assertEquals(manual, applyAutomaticTags(manual, correction))
        assertEquals(original, applyAutomaticTags(original, """{"manual":true}"""))
        assertEquals(original, undoAutomaticTags(original))
    }
    @Test fun rescanningPreservesUndoHistoryAndTheRestoredMetadata() {
        val corrected = applyAutomaticTags(original, correction)
        val scan = ScanResult(listOf(original.copy(title="File title")), setOf(MediaKind.MUSIC), false)
        assertEquals(corrected, mergeScan(listOf(corrected), scan, setOf(MediaKind.MUSIC), emptySet()).single())
        val restored = undoAutomaticTags(corrected)
        assertEquals(restored, mergeScan(listOf(restored), scan, setOf(MediaKind.MUSIC), emptySet()).single())
    }
}
