package fr.nacre.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemaCatalogTest {
    @Test fun animeNullFieldsNeverBecomeLiteralNull() {
        val title = parseCinemaTitle(JSONObject("""{"mal_id":20,"title":"Naruto","synopsis":null,"score":null,"images":{"jpg":{"large_image_url":null}},"aired":{"from":null}}"""), CinemaKind.ANIME)
        assertEquals(20, title.id)
        assertEquals("", title.overview)
        assertEquals("", title.poster)
        assertEquals("", title.year)
        assertEquals("", title.rating)
    }
    @Test fun televisionUsesNameAndKeepsSpecials() {
        val title = parseCinemaTitle(JSONObject("""{"id":42,"name":"Une série","first_air_date":"2020-01-01","poster_path":"/poster.jpg","genres":[{"name":"Drame"}],"seasons":[{"season_number":0},{"season_number":1},{"season_number":1},{}]}"""), CinemaKind.TV)
        assertEquals("Une série", title.title)
        assertEquals("2020", title.year)
        assertEquals(listOf(0, 1), title.seasons)
        assertEquals(listOf("Drame"), title.genres)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", title.poster)
    }
    @Test fun searchPaginationUsesEachProvidersSchemaAndDeduplicates() {
        val anime = parseCinemaSearch(JSONObject("""{"data":[{"mal_id":20,"title":"Naruto"},{"mal_id":20,"title":"Naruto"},{}],"pagination":{"has_next_page":true}}"""), CinemaKind.ANIME)
        assertEquals(1, anime.entries.size)
        assertTrue(anime.more)
        assertFalse(parseCinemaSearch(JSONObject("""{"results":[],"page":2,"total_pages":2}"""), CinemaKind.MOVIE).more)
    }
    @Test fun offersAreCountrySpecificAndRetainPaymentTypes() {
        val offers = parseCinemaAvailability(JSONObject("""{"results":{"FR":{"link":"https://www.themoviedb.org/movie/1/watch?locale=FR","free":[{"provider_name":"Arte"}],"rent":[{"provider_name":"Prime"}],"buy":[{"provider_name":"Prime"}]},"US":{"flatrate":[{"provider_name":"US Only"}]}}}"""), "fr")
        assertEquals(listOf("Gratuit", "Location", "Achat"), offers.offers.map { it.type })
        assertFalse(offers.offers.any { it.name == "US Only" })
        assertTrue(offers.link.startsWith("https://www.themoviedb.org/"))
    }
    @Test fun missingCountryDoesNotFallbackToAnotherMarket() {
        val offers = parseCinemaAvailability(JSONObject("""{"results":{"US":{"free":[{"provider_name":"US Only"}]}}}"""), "FR")
        assertTrue(offers.offers.isEmpty())
        assertEquals("", offers.link)
    }
    @Test fun providerLinkRejectsForeignHostAndEmbeddedCredentials() {
        for (url in listOf("https://themoviedb.org.evil.com/", "javascript:alert(1)", "https://user@www.themoviedb.org/")) {
            val data = JSONObject().put("results", JSONObject().put("FR", JSONObject().put("link", url)))
            assertEquals("", parseCinemaAvailability(data, "FR").link)
        }
    }
    @Test fun episodeNumbersAreNotInventedAndNullAirDatesStayEmpty() {
        val page = parseCinemaEpisodes(JSONObject("""{"data":[{"mal_id":101,"title":"Suite","aired":null},{}],"pagination":{"has_next_page":true}}"""), true)
        assertEquals(101, page.entries.single().number)
        assertEquals("", page.entries.single().date)
        assertTrue(page.more)
        val tv = parseCinemaEpisodes(JSONObject("""{"episodes":[{"episode_number":3,"name":"Trois","air_date":"2026-09-19","overview":"Résumé"}]}"""), false)
        assertEquals("Résumé", tv.entries.single().overview)
        assertFalse(tv.more)
    }
    @Test fun onlyOfficialYoutubeTrailersAreUsedForTmdb() {
        val title = parseCinemaTitle(JSONObject("""{"id":1,"title":"Film","videos":{"results":[{"site":"YouTube","type":"Trailer","official":false,"key":"abcdefghijk"},{"site":"YouTube","type":"Trailer","official":true,"key":"12345678901"}]}}"""), CinemaKind.MOVIE)
        assertEquals("https://www.youtube.com/watch?v=12345678901", title.trailer)
    }
    @Test fun countryUsesIsoCodes() {
        assertEquals("AE", cinemaCountry(" ae "))
        assertEquals("FR", cinemaCountry("ZZ"))
    }
}
