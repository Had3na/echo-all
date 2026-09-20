package fr.nacre.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TorrentSearchTest {
    @Test fun mixedSourcesWithUnknownAndKnownSeedCountsCanBeRanked() {
        val archive = TorrentHit("archive", "Naruto", "Internet Archive")
        val pirateBay = TorrentHit("tpb", "Naruto", "The Pirate Bay", seeders = 12L)
        for (hits in listOf(listOf(archive, pirateBay), listOf(pirateBay, archive))) {
            assertEquals(listOf(pirateBay, archive), rankTorrentHits("naruto", hits))
            assertEquals(pirateBay, automaticTorrent("naruto", hits))
        }
    }
    private fun hit(title: String, seeds: Long? = 5) = TorrentHit(title, title, "test", seeders = seeds)
    @Test fun selectsMatchingTitleRatherThanUnrelatedHighSeedResult() {
        val selected = automaticTorrent("Mon film", listOf(hit("Autre film", 9999), hit("Mon film 1080p", 15), hit("Mon film 720p", 3)))
        assertEquals("Mon film 1080p", selected?.title)
    }
    @Test fun excludesTrailersSamplesAndSoundtracksUnlessRequested() {
        for (name in listOf("Mon film Trailer", "Mon film sample", "Mon film OST", "Mon film soundtrack")) assertNull(automaticTorrent("Mon film", listOf(hit(name))))
        assertNotNull(automaticTorrent("Mon film trailer", listOf(hit("Mon film Trailer"))))
    }
    @Test fun ignoresZeroSeedsAndWeakMatchesForAutomaticDownloads() {
        assertNull(automaticTorrent("Mon film", listOf(hit("Mon film", 0), hit("Documentaire sur Mon film", 12))))
        assertNotNull(automaticTorrent("Mon film", listOf(hit("Mon film", null))))
    }
    @Test fun doesNotAutomaticallyPickASequelWithADifferentTitle() {
        assertNull(automaticTorrent("Avatar", listOf(hit("Avatar The Way of Water 1080p", 1000))))
        assertNotNull(automaticTorrent("Avatar", listOf(hit("Avatar 2009 1080p", 10))))
    }
    @Test fun normalizesAccentsAndReleaseSeparators() { assertEquals(3, torrentMatch("L’été", "L.Ete.2020.1080p")) }
    @Test fun pirateBayBuildsMagnetOnlyForValidVideoResults() {
        val data = JSONArray("""[{"id":"10","name":"Film & VF","info_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","category":"201","seeders":"8","size":"100"},{"id":"0","name":"No results returned"},{"id":"3","name":"App","info_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","category":"303"}]""")
        val result = parsePirateBay(data).single()
        assertEquals(8L, result.seeders)
        assertEquals(100L, result.bytes)
        assertEquals(result.magnet, torrentMagnet(result.magnet))
        assertTrue(result.magnet.contains("Film+%26+VF"))
    }
    @Test fun torrentIdentityIgnoresTrackerAndDisplayNameChanges() {
        val base = "magnet:?xt=urn:btih:" + "a".repeat(40)
        assertEquals(torrentIdentity(base), torrentIdentity(base + "&dn=Film&tr=https%3A%2F%2Fexample.com"))
    }
    @Test fun archiveQueryEscapesUserOperatorsAndOnlyRequestsTorrents() {
        val q = archiveTorrentQuery("Film\" OR mediatype:software")
        assertTrue(q.contains("Film\\\" OR mediatype:software"))
        assertTrue(q.contains("AND mediatype:movies AND format:\"Archive BitTorrent\""))
    }
    @Test fun archiveRejectsInvalidIdentifiers() {
        val parsed = parseArchiveTorrents(JSONObject("""{"response":{"docs":[{"identifier":"film_1","title":"Film"},{"identifier":"../x","title":"Bad"}]}}"""))
        assertEquals("film_1", parsed.single().archiveId)
    }
    @Test fun torznabReadsNamespacedAttributesAndHttpsEnclosures() {
        val result = parseTorznab("""<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><item><title>Film</title><enclosure url="https://example.org/file.torrent" length="1024"/><torznab:attr name="seeders" value="12"/></item></channel></rss>""", "Jackett").single()
        assertEquals(12L, result.seeders); assertEquals(1024L, result.bytes)
        assertEquals("https://example.org/file.torrent", result.url)
    }
    @Test fun torznabRejectsEntitiesErrorsAndNonHttpsPages() {
        assertTrue(runCatching { parseTorznab("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///private'>]><rss>&x;</rss>", "test") }.isFailure)
        assertTrue(runCatching { parseTorznab("<error code='100' description='bad key'/>", "test") }.isFailure)
        assertTrue(parseTorznab("<rss><channel><item><title>Bad</title><link>http://example.org/file</link></item></channel></rss>", "test").isEmpty())
    }
    @Test fun rankingDeduplicatesSameHashAcrossProviders() {
        val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
        assertEquals(1, rankTorrentHits("Film", listOf(TorrentHit("1", "Film", "A", magnet), TorrentHit("2", "Film", "B", magnet + "&dn=Film"))).size)
    }
}
