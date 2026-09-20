package fr.nacre.media
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class YtsSearchTest {
    @Test fun readsQualitiesBuildsMagnetsAndRanksAlongsideArchive() {
        val hits = parseYts(JSONObject("""{"status":"ok","data":{"movies":[{"title":"Film","title_long":"Film (1968)","language":"en","torrents":[{"hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","quality":"1080p","seeds":3,"size_bytes":3000000000},{"hash":"invalid"}]}]}}"""))
        assertEquals(1, hits.size)
        assertEquals(3000000000L, hits.single().bytes)
        assertEquals(3L, hits.single().seeders)
        assertEquals(hits.single().magnet, torrentMagnet(hits.single().magnet))
        assertTrue(hits.single().title.contains("1080p"))
        val archive = TorrentHit("archive", "Film (1968)", "Internet Archive")
        assertEquals(hits.single(), rankTorrentHits("Film", listOf(archive) + hits).first())
    }
    @Test fun handlesEmptyResponseAndRejectsApiErrors() {
        assertTrue(parseYts(JSONObject("""{"status":"ok","data":{"movie_count":0}}""")).isEmpty())
        assertTrue(runCatching { parseYts(JSONObject("""{"status":"error"}""")) }.isFailure)
    }
}
