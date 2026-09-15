package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ToneStackTest {
    private val rate = 44_100

    /** Steady-state gain in dB of a sine through the stack (first half discarded). */
    private fun gainDb(stack: ToneStack, hz: Double, rate: Int = this.rate): Double {
        var input = 0.0; var output = 0.0
        for (i in 0 until rate) {
            val x = .5 * sin(2 * PI * hz * i / rate)
            val y = stack.process(x, 0)
            if (i >= rate / 2) { input += x * x; output += y * y }
        }
        return 10 * log10(output / input)
    }
    private fun stack(eq: IntArray = IntArray(5), shape: SoundShape = SoundShape.NEUTRAL, rate: Int = this.rate) = ToneStack(rate, 2).apply { configure(eq, shape) }

    @Test fun neutralSettingsBypassEverything() {
        val tones = stack()
        assertTrue(tones.bypass)
        assertEquals(.123, tones.process(.123, 1), 0.0)
    }
    @Test fun equalizerBandBoostsItsFrequencyOnly() {
        val tones = stack(eq = intArrayOf(0, 0, 6, 0, 0))
        assertEquals(6.0, gainDb(tones, 1_000.0), .5)
        assertEquals(0.0, gainDb(stack(eq = intArrayOf(0, 0, 6, 0, 0)), 60.0), 1.0)
    }
    @Test fun clubBassCutRemovesLowsAndKeepsHighs() {
        val shape = SoundShape(bassDb = BASS_KILL_DB)
        assertTrue(gainDb(stack(shape = shape), 50.0) < -20)
        assertEquals(0.0, gainDb(stack(shape = shape), 5_000.0), 1.0)
    }
    @Test fun filterSweepStagesCutTheRightSide() {
        val highPass = SoundShape(highPassHz = 1_500f)
        assertTrue(gainDb(stack(shape = highPass), 100.0) < -30)
        assertEquals(0.0, gainDb(stack(shape = highPass), 10_000.0), 1.5)
        val lowPass = SoundShape(lowPassHz = 350f)
        assertTrue(gainDb(stack(shape = lowPass), 6_000.0) < -30)
        assertEquals(0.0, gainDb(stack(shape = lowPass), 60.0), 1.5)
    }
    @Test fun openLowPassAndBandsAboveNyquistAreSkipped() {
        assertTrue(stack(shape = SoundShape(lowPassHz = 19_900f)).bypass)
        assertTrue(stack(eq = intArrayOf(0, 0, 0, 0, 12), rate = 22_050).bypass)
    }
    @Test fun extremeSettingsStayStable() {
        val tones = stack(eq = intArrayOf(12, -12, 12, -12, 12), shape = SoundShape(BASS_KILL_DB, 1_500f, 350f))
        var noise = 12345L; var energy = 0.0
        repeat(rate * 2) {
            noise = noise * 6364136223846793005L + 1442695040888963407L
            val y = tones.process((noise ushr 33).toDouble() / (1L shl 31) - .5, it % 2)
            assertTrue(y.isFinite()); energy += y * y
        }
        assertTrue(sqrt(energy / (rate * 2)) < 10)
    }
}
