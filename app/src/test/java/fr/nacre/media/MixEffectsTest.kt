package fr.nacre.media
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class MixEffectsTest {
    private fun linear(db: Float) = 10f.pow(db / 20f)

    @Test fun clubHandsTheLowEndOverWithoutStackingOrDroppingIt() {
        assertEquals(0f, soundShape(0f, "club", false).bassDb, .001f)
        assertEquals(BASS_KILL_DB, soundShape(0f, "club", true).bassDb, .001f)
        assertEquals(BASS_KILL_DB, soundShape(1f, "club", false).bassDb, .001f)
        assertTrue(soundShape(1f, "club", true).neutral)
        for (i in 0..100) {
            val p = i / 100f
            val gains = shapedMix(p, "club")
            // What actually reaches the ears: each deck's own gain times whatever is left of its low end.
            val low = gains.outgoing * linear(soundShape(p, "club", false).bassDb) +
                gains.incoming * linear(soundShape(p, "club", true).bassDb)
            // Never above what a single track had, and never the hole the offset curves used to leave.
            assertTrue("p=$p low=$low", low in .85f..1.02f)
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
    @Test fun anOrdinaryFadeEasesBothLowEndsInsteadOfStackingThem() {
        // Whoever is alone at either end is heard untouched.
        assertTrue(soundShape(0f, "smooth", false).neutral)
        assertTrue(soundShape(1f, "smooth", true).neutral)
        // Half way, 3 dB off each is exactly what two equal sources add up to.
        assertEquals(-3f, soundShape(.5f, "smooth", false).bassDb, .01f)
        assertEquals(-3f, soundShape(.5f, "smooth", true).bassDb, .01f)
        // The low shelf is the only thing touched: no filter sweep on a plain fade.
        for (style in listOf("smooth", "linear", "inconnu")) for (incoming in listOf(true, false)) for (i in 0..100) {
            val shape = soundShape(i / 100f, style, incoming)
            assertEquals(0f, shape.highPassHz, 0f)
            assertEquals(0f, shape.lowPassHz, 0f)
            assertTrue("$style p=$i", shape.bassDb in BASS_EASE_DB..0f)
        }
    }

    @Test fun aHardCutHasNoOverlapToCleanUp() {
        for (incoming in listOf(true, false)) for (i in 0..100) assertTrue(soundShape(i / 100f, "cut", incoming).neutral)
    }
    @Test fun effectsOnlyRemoveEnergy() {
        for (style in listOf("club", "sweep", "smooth", "linear")) for (incoming in listOf(true, false)) for (p in -10..110) {
            val shape = soundShape(p / 100f, style, incoming)
            assertTrue(shape.bassDb in BASS_KILL_DB..0f)
            assertTrue(shape.highPassHz >= 0f && shape.lowPassHz >= 0f)
        }
    }
}
