package fr.nacre.media
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class MixEffectsTest {
    private fun linear(db: Float) = 10f.pow(db / 20f)

    @Test fun clubSwapsBassWithoutStackingBothLowEnds() {
        assertEquals(0f, soundShape(0f, "club", false).bassDb, .001f)
        assertEquals(BASS_KILL_DB, soundShape(0f, "club", true).bassDb, .001f)
        assertEquals(BASS_KILL_DB, soundShape(1f, "club", false).bassDb, .001f)
        assertTrue(soundShape(1f, "club", true).neutral)
        for (i in 0..100) {
            val p = i / 100f
            assertTrue("p=$p", linear(soundShape(p, "club", false).bassDb) + linear(soundShape(p, "club", true).bassDb) <= 1.05f)
        }
    }
    @Test fun sweepThinsOutgoingAndOpensIncoming() {
        assertTrue(soundShape(0f, "sweep", false).neutral)
        assertTrue(soundShape(1f, "sweep", true).neutral)
        assertEquals(350f, soundShape(0f, "sweep", true).lowPassHz, 1f)
        var previousHigh = 0f; var previousLow = 0f
        for (i in 1..99) {
            val p = i / 100f
            val high = soundShape(p, "sweep", false).highPassHz; val low = soundShape(p, "sweep", true).lowPassHz
            assertTrue(high > previousHigh && low > previousLow)
            previousHigh = high; previousLow = low
        }
    }
    @Test fun otherStylesNeverFilter() {
        for (style in listOf("smooth", "linear", "cut", "inconnu")) for (incoming in listOf(true, false)) assertTrue(soundShape(.5f, style, incoming).neutral)
    }
    @Test fun effectsOnlyRemoveEnergy() {
        for (style in listOf("club", "sweep")) for (incoming in listOf(true, false)) for (p in -10..110) {
            val shape = soundShape(p / 100f, style, incoming)
            assertTrue(shape.bassDb in BASS_KILL_DB..0f)
            assertTrue(shape.highPassHz >= 0f && shape.lowPassHz >= 0f)
        }
    }
}
