package fr.nacre.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineCatalogTest {
    @Test fun fileNamesGiveAFirstGuess() {
        assertEquals("Titre" to "Artiste", guessTags("03 - Artiste - Titre.mp3", ""))
        assertEquals("Mon titre" to "Groupe", guessTags("Mon_titre.flac", "Groupe"))
        assertEquals("Seul" to "", guessTags("12. Seul", ""))
    }
    @Test fun musicBrainzQueryIsQuotedAndEscaped() {
        assertEquals("recording:\"Say \\\"Hi\\\"\" AND artist:\"A\\\\B\"", recordingQuery(" Say \"Hi\" ", "A\\B"))
        assertEquals("recording:\"Solo\"", recordingQuery("Solo", " "))
    }
    @Test fun aShelfKeepsItsOwnFilterAndTheUsualGuards() {
        val shelf = archiveRowQuery("collection:(feature_films)")
        assertTrue(shelf.startsWith("(collection:(feature_films)) AND "))
        assertTrue("mediatype:(movies)" in shelf)
        // A shelf must not reach lending-only items any more than a search does.
        assertTrue("-access-restricted-item:(true)" in shelf)
        assertEquals(archiveSearchQuery("  ", MediaKind.VIDEO), shelf.substringAfter(") AND "))
    }

    @Test fun descriptionsLoseTheirMarkup() {
        assertEquals("Un film.\nDe 1954.", plainText("<p>Un  film.</p><br/>De <b>1954</b>."))
        assertEquals("L\u2019ami & moi", plainText("L&#39;ami &amp; moi"))
        assertEquals("", plainText("   <div>  </div> "))
    }

    @Test fun recordingsPreferOfficialAlbums() {
        val json = JSONObject("""{"recordings":[{"id":"r1","score":97,"title":"Morceau","length":215000,
            "artist-credit":[{"name":"Duo A","joinphrase":" & "},{"name":"B"}],
            "releases":[{"id":"single","title":"Single","status":"Official","date":"2001","release-group":{"id":"g1","primary-type":"Single"}},
                        {"id":"album","title":"Album","status":"Official","date":"1999-05-01","release-group":{"id":"g2","primary-type":"Album"}},
                        {"id":"boot","title":"Live pirate","status":"Bootleg","release-group":{"id":"g3","primary-type":"Album"}}]},
            {"id":"r2","score":40,"title":"","releases":[]},
            {"id":"r3","score":60,"title":"Sans sortie","first-release-date":"2010-01-01"}]}""")
        val results = parseRecordings(json)
        assertEquals(2, results.size)
        val best = results.first()
        assertEquals(TagCandidate("Morceau", "Duo A & B", "Album", "1999", "album", "g2", 97, 215000), best)
        assertEquals(listOf("https://coverartarchive.org/release-group/g2/front-500", "https://coverartarchive.org/release/album/front-500"), best.covers)
        assertEquals("2010", results[1].year)
        assertEquals(emptyList<String>(), results[1].covers)
    }
    @Test fun archiveSearchOnlyAsksForFreeItems() {
        val music = archiveSearchQuery("jazz (live)", MediaKind.MUSIC)
        assertEquals("(jazz live) AND mediatype:(audio) AND (licenseurl:* OR collection:(etree)) AND -access-restricted-item:(true)", music)
        val video = archiveSearchQuery("  ", MediaKind.VIDEO)
        assertTrue(video.startsWith("mediatype:(movies) AND (licenseurl:*"))
        assertTrue("-access-restricted-item:(true)" in video)
    }
    @Test fun licencesAreReadable() {
        assertEquals("CC BY-NC-ND 3.0", licenseLabel("http://creativecommons.org/licenses/by-nc-nd/3.0/"))
        assertEquals("Domaine public (CC0)", licenseLabel("https://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals("Domaine public", licenseLabel("http://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals("Live Music Archive · partage autorisé", licenseLabel("", listOf("etree")))
    }
    @Test fun searchResultsHandleArraysAndMissingFields() {
        val json = JSONObject("""{"response":{"docs":[
            {"identifier":"a1","title":"Album","creator":["X","Y"],"year":2018,"licenseurl":"http://creativecommons.org/licenses/by/4.0/","collection":["netlabels"]},
            {"identifier":"live1","title":"Concert","date":"1994-03-02","collection":["etree"]},
            {"title":"sans identifiant"}]}}""")
        assertEquals(listOf(
            ArchiveResult("a1", "Album", "X, Y", "2018", "CC BY 4.0"),
            ArchiveResult("live1", "Concert", "", "1994", "Live Music Archive · partage autorisé")), parseArchiveSearch(json))
        assertEquals("https://archive.org/services/img/a%20b", ArchiveResult("a b", "", "", "", "").thumbnail)
    }
    @Test fun lengthsInSecondsOrClockFormat() {
        assertEquals(137_250, parseLength("137.25"))
        assertEquals(137_000, parseLength("2:17"))
        assertEquals(3_723_000, parseLength("1:02:03"))
        assertEquals(0, parseLength("?")); assertEquals(0, parseLength("1:x"))
    }
    @Test fun albumKeepsOneFormatInTrackOrder() {
        val json = JSONObject("""{"metadata":{"title":"Disque","creator":"Groupe","date":"2018-01-29","licenseurl":"http://creativecommons.org/licenses/by-sa/3.0/"},
            "files":[
              {"name":"02 - B.flac","format":"Flac","source":"original","title":"2 - Groupe - Deuxième","track":"2/3","length":"200","size":"9000000"},
              {"name":"02 - B.mp3","format":"VBR MP3","source":"derivative","title":"2 - Groupe - Deuxième","track":"2/3","length":"200.5","size":"3000000"},
              {"name":"01 - A #1.mp3","format":"VBR MP3","source":"derivative","title":"1 - Groupe - Premier","track":"1","length":"2:00","size":"2000000","artist":"Groupe"},
              {"name":"cover.jpg","format":"JPEG"},
              {"name":"__ia_thumb.jpg","format":"Item Tile"},
              {"name":"disque.thumbs/x.mp3","format":"VBR MP3"}]}""")
        val item = parseArchiveItem("disque 2018", json, MediaKind.MUSIC)
        assertEquals("CC BY-SA 3.0", item.license)
        assertEquals("2018", item.year)
        assertEquals(listOf("Premier", "Deuxième"), item.files.map { it.title })
        assertEquals(listOf("mp3", "mp3"), item.files.map { it.extension })
        val first = item.files.first()
        assertEquals("Groupe", first.artist); assertEquals("Disque", first.album); assertEquals(1, first.track); assertEquals(120_000, first.durationMs)
        assertEquals("https://archive.org/download/disque%202018/01%20-%20A%20%231.mp3", first.url)
        assertEquals("https://archive.org/services/img/disque%202018", item.cover)
    }
    @Test fun videoFallsBackToExtensionsWhenFormatIsUnknown() {
        val json = JSONObject("""{"metadata":{"title":"Film"},"files":[{"name":"film.webm","format":"Autre"},{"name":"notes.txt","format":"Text"}]}""")
        assertEquals(listOf("film.webm"), parseArchiveItem("film", json, MediaKind.VIDEO).files.map { it.name })
        assertTrue(parseArchiveItem("film", json, MediaKind.MUSIC).files.isEmpty())
    }
    @Test fun radioDirectoryKeepsWorkingHttpsStreamsOnly() {
        val json = org.json.JSONArray("""[
            {"stationuuid":"u1","name":" Radio Une ","url_resolved":"https://stream.example/une.mp3","favicon":"https://example/logo.png","tags":"pop, rock,,pop","countrycode":"fr","codec":"MP3","bitrate":128,"lastcheckok":1},
            {"stationuuid":"u2","name":"En clair","url_resolved":"http://stream.example/clair.mp3","lastcheckok":1},
            {"stationuuid":"u3","name":"Cassée","url_resolved":"https://stream.example/cassee.mp3","lastcheckok":0},
            {"stationuuid":"u4","name":"Doublon","url_resolved":"https://stream.example/une.mp3","lastcheckok":1},
            {"stationuuid":"u5","name":"Sans logo sûr","url":"https://stream.example/deux.m3u8","favicon":"http://example/logo.png","codec":"UNKNOWN","bitrate":0}]""")
        val stations = parseStations(json)
        assertEquals(listOf("Radio Une", "Sans logo sûr"), stations.map { it.name })
        assertEquals(RadioStation("u1", "Radio Une", "https://stream.example/une.mp3", "https://example/logo.png", listOf("pop", "rock"), "FR", "MP3", 128), stations[0])
        assertEquals("FR · pop, rock · MP3 128 kb/s", stations[0].details)
        assertEquals("", stations[1].favicon); assertEquals("", stations[1].details)
    }
    @Test fun radioSearchAlwaysAsksForHttpsAndWorkingStations() {
        val path = radioSearchPath("fip & co", "jazz", "FR")
        assertTrue(path.startsWith("/json/stations/search?hidebroken=true&is_https=true"))
        assertTrue("&name=fip%20%26%20co" in path); assertTrue("&tag=jazz" in path); assertTrue("&countrycode=FR" in path)
        assertFalse("&name=" in radioSearchPath(" ", "", ""))
    }
    @Test fun downloadedFileNamesAreSafe() {
        assertEquals("AC DC - Back in Black", safeFileName("AC/DC - Back in: \"Black\"?"))
        assertEquals("media", safeFileName("..."))
        assertTrue(safeFileName("x".repeat(300)).length <= 90)
        assertEquals("audio/mpeg", mimeFor("MP3")); assertEquals("video/mp4", mimeFor("mp4"))
    }
}
