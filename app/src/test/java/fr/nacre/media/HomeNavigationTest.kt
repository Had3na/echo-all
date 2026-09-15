package fr.nacre.media
import org.junit.Assert.*
import org.junit.Test
class HomeNavigationTest {
    @Test fun restoredOrderKeepsValidSectionsAndAddsMissingOnes() {
        assertEquals(listOf("favorites", "playlists", "recent", "resume"), homeSections("favorites,favorites,unknown,playlists"))
    }
    @Test fun wheelSelectsUpperSpokesAndCancelsNearCenter() {
        assertEquals(2, wheelTarget(0f, -112f, 112f))
        assertEquals(1, wheelTarget(-79f, -79f, 112f))
        assertEquals(3, wheelTarget(79f, -79f, 112f))
        assertEquals(0, wheelTarget(-105f, -38f, 112f))
        assertEquals(4, wheelTarget(105f, -38f, 112f))
        assertNull(wheelTarget(0f, 0f, 112f))
        assertNull(wheelTarget(0f, 80f, 112f))
        assertNull(wheelTarget(0f, -300f, 112f))
    }
}
