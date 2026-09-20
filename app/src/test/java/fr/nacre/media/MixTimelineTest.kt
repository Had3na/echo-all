package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class MixTimelineTest {
    private val styles = listOf("smooth", "linear", "cut", "club", "sweep")

    @Test fun automaticMixRunsForItsDurationThenEnds() {
        val mix = MixTimeline().apply { begin(now = 1_000, durationMs = 4_000, manual = false) }
        val first = mix.frame(1_000, "linear")
        assertEquals(0f, first.progress, .001f); assertEquals(1f, first.outgoing.gain, .001f); assertEquals(0f, first.incoming.gain, .001f); assertFalse(first.done)
        val middle = mix.frame(3_000, "linear")
        assertEquals(.5f, middle.progress, .001f); assertFalse(middle.done)
        val last = mix.frame(5_000, "linear")
        assertEquals(1f, last.progress, .001f); assertTrue(last.done)
    }
    @Test fun finishedAutomaticMixLeavesIncomingAtFullVolumeWithItsUsualSound() {
        for (style in styles) {
            val mix = MixTimeline().apply { begin(0, 3_000, false) }
            val end = mix.frame(3_000, style)
            assertTrue(style, end.done)
            assertEquals(style, 1f, end.incoming.gain, .001f)
            assertTrue(style, end.incoming.shape.neutral)
        }
        assertEquals(DeckMix(1f, SoundShape.NEUTRAL), MixTimeline.SETTLED)
    }
    @Test fun gainsStayBoundedAndKeepTheirLoudnessAtEveryInstant() {
        for (style in styles) {
            val mix = MixTimeline().apply { begin(0, 2_000, false) }
            for (t in -500L..2_500L step 50) {
                val frame = mix.frame(t, style)
                assertTrue(frame.outgoing.gain in 0f..1f && frame.incoming.gain in 0f..1f)
                // Equal power: the two decks together stay as loud as one deck alone.
                val power = frame.outgoing.gain * frame.outgoing.gain + frame.incoming.gain * frame.incoming.gain
                assertEquals(1f, power, .001f)
            }
        }
    }
    @Test fun manualBlendFollowsSliderAndNeverEndsOnItsOwn() {
        val mix = MixTimeline().apply { begin(0, 3_000, true) }
        mix.move(.4f)
        val frame = mix.frame(60_000, "club")
        assertEquals(.4f, frame.progress, .001f); assertEquals(0.7746f, frame.outgoing.gain, .001f); assertFalse(frame.done)
        mix.move(1.7f); assertEquals(1f, mix.blend, 0f); assertTrue(mix.frame(60_000, "club").done)
        mix.move(-1f); assertEquals(0f, mix.blend, 0f)
    }
    @Test fun releasingManualBlendContinuesFromSliderWithoutJump() {
        val mix = MixTimeline().apply { begin(0, 4_000, true) }
        mix.move(.6f)
        mix.release(now = 10_000)
        assertFalse(mix.manual)
        assertEquals(.6f, mix.progress(10_000), .001f)
        assertEquals(.8f, mix.progress(12_000), .001f)
        assertTrue(mix.frame(14_000, "sweep").done)
        mix.release(20_000) // no-op when already automatic
        assertEquals(1f, mix.progress(20_000), .001f)
    }
    @Test fun stopClearsManualState() {
        val mix = MixTimeline().apply { begin(0, 3_000, true); move(.5f) }
        mix.stop()
        assertFalse(mix.manual); assertEquals(0f, mix.blend, 0f)
    }
    @Test fun mixWindowEndsBeforeExitPoint() {
        assertEquals(5_000, mixWindow(5_000, 10_000, 0, 60_000, 1f))
        assertEquals(2_000, mixWindow(5_000, 58_000, 0, 60_000, 1f))
        assertEquals(1_000, mixWindow(5_000, 20_000, 22_000, 60_000, 2f))
        assertEquals(5_000, mixWindow(5_000, 10_000, 0, Long.MIN_VALUE + 1, 1f))
        assertEquals(1, mixWindow(5_000, 60_000, 0, 60_000, 1f))
        assertEquals(1, mixWindow(0, 0, 0, 60_000, 1f))
    }
    @Test fun remainingTimeUsesCueOutUnlessLooping() {
        val cued = TrackTools(cueIn = 1_000, cueOut = 50_000)
        assertEquals(40_000, remainingMs(cued, 10_000, 60_000, 1f))
        assertEquals(20_000, remainingMs(cued, 10_000, 60_000, 2f))
        assertEquals(50_000, remainingMs(cued.copy(loop = true), 10_000, 60_000, 1f))
        assertEquals(Long.MAX_VALUE, remainingMs(TrackTools(), 10_000, Long.MIN_VALUE + 1, 1f))
    }
    @Test fun automaticPreparationStartsAFewSecondsAhead() {
        assertTrue(autoMixDue(8_000, 5)); assertTrue(autoMixDue(1, 5))
        assertFalse(autoMixDue(8_001, 5)); assertFalse(autoMixDue(0, 5)); assertFalse(autoMixDue(1_000, 0))
    }
}
