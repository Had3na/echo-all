package fr.nacre.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {
    @Test fun acceptsDirectHttpsWithQuery() {
        assertTrue(validStreamUrl("https://media.example.org/live.m3u8?token=a%2Bb"))
        assertTrue(validStreamUrl(" HTTPS://media.example.org:8443/music.mp3 "))
    }
    @Test fun rejectsOtherSchemesAndMissingHosts() {
        listOf("", "file:///music.mp3", "content://media/1", "http://nas/music.mp3", "https:///music.mp3", "music.mp3", "https://bad host/file")
            .forEach { assertFalse(it, validStreamUrl(it)) }
    }
    @Test fun rejectsCredentialsInUrl() {
        assertFalse(validStreamUrl("https://user:password@nas.example.org/music.mp3"))
    }
}
