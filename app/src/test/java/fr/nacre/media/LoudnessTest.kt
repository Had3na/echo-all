package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class LoudnessTest {
    private fun sine(meter: LoudnessMeter, dbfs: Double, seconds: Int, hz: Double = 1_000.0) {
        val amplitude = 10.0.pow(dbfs / 20)
        repeat(meter.rate * seconds) { i ->
            val x = amplitude * sin(2 * PI * hz * i / meter.rate)
            meter.add(x, 0); meter.add(x, 1); meter.endFrame()
        }
    }

    @Test fun referenceSineMeasuresItsLevel() {
        // EBU Tech 3341: a stereo 1 kHz sine at -23 dBFS reads -23 LUFS.
        for (rate in listOf(48_000, 44_100)) {
            val meter = LoudnessMeter(rate, 2)
            sine(meter, -23.0, 20)
            assertEquals("rate $rate", -23.0, meter.integrated()!!, .3)
            assertEquals(20.0, meter.seconds, .5)
        }
    }
    @Test fun silenceHasNoLoudness() {
        val meter = LoudnessMeter(48_000, 2)
        repeat(48_000 * 5) { meter.add(0.0, 0); meter.add(0.0, 1); meter.endFrame() }
        assertNull(meter.integrated())
    }
    @Test fun quietPassagesDoNotDragTheMeasureDown() {
        val meter = LoudnessMeter(48_000, 2)
        sine(meter, -20.0, 10); sine(meter, -60.0, 10)
        assertEquals(-20.0, meter.integrated()!!, .5)
    }
    @Test fun limiterNeverExceedsCeilingAndLeavesQuietAudioAlone() {
        val limiter = Limiter(48_000)
        repeat(48_000) { i ->
            val peak = if (i % 4_800 < 2_400) 2.5 * kotlin.math.abs(sin(i * .01)) else .3
            assertTrue(peak * limiter.gainFor(peak) <= .97 + 1e-9)
        }
        val fresh = Limiter(48_000)
        repeat(1_000) { assertEquals(1.0, fresh.gainFor(.5), 0.0) }
    }
    @Test fun normalizationPrefersTagsThenMeasurementAndStaysBounded() {
        assertEquals(-.23f, normalizationDb(-7.23f, -5.0), .001f)
        assertEquals(-3f, normalizationDb(null, -8.0), .001f)
        assertEquals(6f, normalizationDb(null, -30.0), 0f)
        assertEquals(-12f, normalizationDb(-25f, null), 0f)
        assertEquals(0f, normalizationDb(null, null), 0f)
    }
    @Test fun replayGainIsReadFromId3AndVorbisTags() {
        assertEquals(-7.23f, replayGainDb(listOf("TIT2: description=null: values=[Titre]", "TXXX: description=REPLAYGAIN_TRACK_GAIN: values=[-7.23 dB]"))!!, .001f)
        assertEquals(2.5f, replayGainDb(listOf("VC: replaygain_track_gain=+2,5 dB"))!!, .001f)
        assertNull(replayGainDb(listOf("VC: REPLAYGAIN_ALBUM_GAIN=-3.00 dB", "VC: ARTIST=Groupe")))
        assertNull(replayGainDb(listOf("TXXX: description=REPLAYGAIN_TRACK_GAIN: values=[-99 dB]")))
    }
}
