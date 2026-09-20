package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class ListeningFeaturesTest {
    @Test fun lrcParsesRepeatedTimestampsFractionsAndOffsets() {
        val lines = parseLrc("[ar:Test]\r\n[offset:-250]\r\n[00:01.50][01:02.125]First\r\n[00:03]Second")
        assertEquals(listOf(1250L, 2750L, 61875L), lines.map { it.timeMs })
        assertEquals(listOf("First", "Second", "First"), lines.map { it.text })
    }
    @Test fun lrcRejectsBrokenTimesAndKeepsInstrumentalGaps() {
        val lines = parseLrc("plain\n[00:72]bad\n[00:01]word\n[00:02]\n[00:03.5]next")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
        assertEquals(3500L, lines[2].timeMs)
    }
    @Test fun activeLineFollowsSeeksAndHasNoEarlyHighlight() {
        val lines = parseLrc("[00:02]one\n[00:05]two\n[00:09]three")
        assertEquals(-1, activeLyric(lines, 1999))
        assertEquals(0, activeLyric(lines, 2000))
        assertEquals(2, activeLyric(lines, 10000))
        assertEquals(0, activeLyric(lines, 3500))
        assertEquals(-1, activeLyric(emptyList(), 5000))
    }
    @Test fun autoMatchingRejectsUnknownArtistsAndDifferentVersions() {
        val exact = TagCandidate("Étoile", "Artiste", "Album", "2020", "r", "g", 100, 180000)
        assertEquals(exact, automaticTag("Etoile", "ARTISTE", 180500, listOf(exact)))
        assertNull(automaticTag("Etoile", "", 180000, listOf(exact)))
        assertNull(automaticTag("Etoile", "Artiste", 200000, listOf(exact)))
        assertNull(automaticTag("Etoile", "Artiste", 180000, listOf(exact.copy(title = "Étoile (Live)"))))
        assertNull(automaticTag("Etoile", "Artiste", 180000, listOf(exact, exact.copy(album = "Compilation"))))
        assertNull(automaticTag("Etoile", "Artiste", 180000, listOf(exact.copy(score = 70))))
    }
    @Test fun videoSectionsDefaultByLocationButRespectManualMoves() {
        val local = LibraryItem("content://media/video/1", "Family", MediaKind.VIDEO)
        assertEquals("daily", local.videoSpace())
        assertEquals("stream", local.copy(uri = "https://example.org/video.mp4").videoSpace())
        assertEquals("stream", local.copy(videoSection = "stream").videoSpace())
        assertEquals("Non classées", local.videoGroup())
        assertEquals("Voyage", local.copy(videoCategory = "Voyage").videoGroup())
    }
    @Test fun scanPreservesVideoOrganization() {
        val scanned = LibraryItem("content://media/video/1", "Clip", MediaKind.VIDEO, scanned = true)
        val old = scanned.copy(videoSection = "stream", videoCategory = "Mes films")
        val result = mergeScan(listOf(old), ScanResult(listOf(scanned), setOf(MediaKind.VIDEO), false), setOf(MediaKind.VIDEO), emptySet())
        assertEquals(old, result.single())
    }
    @Test fun customCategoriesAreScopedToTheRightVideoSpace() {
        val entries = listOf(LibraryItem("content://1", "A", MediaKind.VIDEO, videoCategory = "Vacances"), LibraryItem("https://example.org/a", "B", MediaKind.VIDEO, videoCategory = "Concerts"))
        assertTrue("Vacances" in videoGroups(entries, "daily"))
        assertFalse("Concerts" in videoGroups(entries, "daily"))
        assertTrue("Concerts" in videoGroups(entries, "stream"))
        assertEquals(videoGroups(entries, "stream").distinct(), videoGroups(entries, "stream"))
    }
}
