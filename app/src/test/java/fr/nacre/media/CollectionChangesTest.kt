package fr.nacre.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CollectionChangesTest {
    @Test fun normalizingKeepsOrderAndRemovesDuplicates() {
        val list = SavedList("a", "A", listOf("three", "one", "three"), style = "inconnu", seconds = 99).normalized()
        assertEquals(listOf("three", "one"), list.uris)
        assertEquals(60, list.seconds)
        assertEquals("smooth", list.style)
        assertEquals(list, list.normalized())
    }
    @Test fun randomPlaybackKeepsEveryTrackAndDoesNotEditSavedOrder() {
        val saved = (1..30).toList()
        val mixed = collectionOrder(saved, true, kotlin.random.Random(42))
        assertEquals(saved.toSet(), mixed.toSet())
        assertEquals(saved.size, mixed.size)
        assertNotEquals(saved, mixed)
        assertEquals((1..30).toList(), saved)
        assertEquals(saved, collectionOrder(saved, false))
    }
    @Test fun waveSurvivesStorage() {
        val wave = listOf(0f, .25f, 1f, .125f)
        assertEquals(wave, decodeWave(encodeWave(wave)))
        assertEquals(emptyList<Float>(), decodeWave(encodeWave(emptyList())))
    }

    private val legacy: Map<String, Any?> = mapOf(
        "lists" to """[{"id":"l1","name":"Soirée","uris":["content://a","content://b","content://a"],"style":"club","seconds":8,"shuffle":true}]""",
        "track:content://a" to """{"bpm":124.5,"confidence":0.8,"in":1000,"out":90000,"loopIn":0,"loopOut":0,"loop":false,"wave":[0.1,0.5]}""",
        "marks:content://a" to "3000,1000,3000",
        "pad:content://a:2" to 45_000L,
        "count:content://a" to 3, "last:content://a" to 1_700_000_000_000L,
        "art:list:l1" to "content://media/picker/1",
        "art:content://b" to "file:///sdcard/x.jpg"
    )

    @Test fun oldPreferencesMigrateCompletely() {
        val data = parseStudio(legacy, strict = false)
        assertEquals(listOf(SavedList("l1", "Soirée", listOf("content://a", "content://b"), "club", 8, true)), data.lists)
        assertEquals(TrackTools(124.5f, .8f, 1000, 90000, wave = listOf(.1f, .5f)), data.tracks["content://a"])
        assertEquals(listOf(1000L, 3000L), data.marks["content://a"])
        assertEquals(45_000L, data.pads[PadSlot("content://a", 2)])
        assertEquals(PlayStat(3, 1_700_000_000_000L), data.plays["content://a"])
        assertEquals(mapOf("list:l1" to "content://media/picker/1"), data.covers)
    }
    @Test fun damagedEntriesAreSkippedDuringMigrationButRejectedOnImport() {
        val damaged = legacy + ("track:content://c" to """{"bpm":120,"in":5000,"out":1000}""") + ("track:content://d" to "pas du json")
        val migrated = parseStudio(damaged, strict = false)
        assertEquals(setOf("content://a"), migrated.tracks.keys)
        assertEquals(1, migrated.lists.size)
        assertThrows(Exception::class.java) { parseStudio(damaged, strict = true) }
    }
    @Test fun backupLayoutRoundTrips() {
        val original = parseStudio(legacy, strict = false).copy(covers = mapOf("list:l1" to "/data/covers/x.jpg"))
        val json = original.toLegacyJson { "data:image/jpeg;base64,AAAA" }
        val back = parseStudio(json.keys().asSequence().associateWith { json.get(it) }, strict = true)
        assertEquals(original.lists, back.lists)
        assertEquals(original.tracks, back.tracks)
        assertEquals(original.marks, back.marks)
        assertEquals(original.pads, back.pads)
        assertEquals(original.plays, back.plays)
        assertEquals(mapOf("list:l1" to "data:image/jpeg;base64,AAAA"), back.covers)
        assertTrue(JSONObject(json.toString()).has("pad:content://a:2"))
    }
}
