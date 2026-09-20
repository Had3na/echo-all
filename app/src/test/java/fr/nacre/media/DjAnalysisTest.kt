package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class DjAnalysisTest {
    @Test fun detectsRegular120BpmPulseTrain() {
        val signal=List(3000){ if(it%50==0) 1f else .01f }
        val estimate=estimateTempo(signal)
        assertEquals(120f,estimate.bpm,1f);assertTrue(estimate.confidence>.8f)
    }
    @Test fun detectsSlowerPulseTrain() {
        val estimate=estimateTempo(List(3000){if(it%67==0)1f else .01f})
        assertEquals(90f,estimate.bpm,1.5f)
    }
    @Test fun silenceAndShortClipsDoNotInventTempo() {
        assertEquals(0f,estimateTempo(List(3000){0f}).bpm,0f)
        assertEquals(0f,estimateTempo(List(300){if(it%50==0)1f else 0f}).bpm,0f)
        assertEquals(0f,estimateTempo(List(3000){.5f}).bpm,0f)
    }
    @Test fun tapNeedsSeveralConsistentIntervals() {
        assertEquals(0f,tappedBpm(listOf(0,500,1000)),0f)
        assertEquals(120f,tappedBpm(listOf(0,500,1000,1500,2000)),.1f)
        assertEquals(0f,tappedBpm(listOf(0,500,1000,4500)),0f)
    }
    @Test fun syncMatchesEffectiveTempoAndRejectsExtremeStretch() {
        // A speed the listener chose carries over untouched: only the matching sits on top of it.
        assertEquals(1.1f, matchedSpeed(120f, 1.1f, 120f)!!, .0001f)
        assertEquals(1.1f * 120f / 124f, matchedSpeed(120f, 1.1f, 124f)!!, .0001f)
        // A small gap is closed: 124 under 120 is a 3 % nudge.
        assertEquals(120f / 124f, matchedSpeed(120f, 1f, 124f)!!, .0001f)
        // A 20 % gap is not beat matching, it is a different piece of music: left alone.
        assertNull(matchedSpeed(120f, 1f, 100f))
        // Counted at double time, 90 under 178 needs about 1 %, so it matches after all.
        assertEquals(178f / 180f, matchedSpeed(178f, 1f, 90f)!!, .0001f)
        assertNull(matchedSpeed(180f, 1f, 80f)); assertNull(matchedSpeed(0f, 1f, 100f))
        // Whatever comes out, no track is pushed far from the speed already being heard.
        for (base in listOf(.8f, 1f, 1.25f)) for (from in 60..200 step 5) for (to in 60..200 step 5) {
            val speed = matchedSpeed(from.toFloat(), base, to.toFloat()) ?: continue
            assertTrue("$from -> $to at $base = $speed",
                kotlin.math.abs(speed / base - 1f) <= MAX_TEMPO_STRETCH + .0001f)
        }
    }
    @Test fun theBeatGridIsFoundWhereTheAttacksAre() {
        // A pulse every 50 hundredths at 100 Hz is 120 BPM, starting 17 hundredths in.
        val energy = List(1200) { if ((it - 17) >= 0 && (it - 17) % 50 == 0) 1f else 0f }
        assertEquals(170.0, beatPhaseMs(energy, 120f).toDouble(), 10.0)
        // Nothing to lock on to, or a tempo nobody measured: no claim is made.
        assertEquals(0L, beatPhaseMs(List(1200) { .5f }, 120f))
        assertEquals(0L, beatPhaseMs(energy, 0f))
        assertEquals(0L, beatPhaseMs(listOf(1f, 0f), 120f))
    }

    @Test fun aFadeLastsAWholeNumberOfBeats() {
        // 6 s at 120 BPM is 12 beats exactly: three bars, left alone.
        assertEquals(6_000L, snapMixToBeats(6_000, 120f))
        // 6 s at 128 BPM is 12.8 beats; twelve is a bar group and within a beat, so twelve it is.
        assertEquals((12 * 60_000f / 128f).toLong(), snapMixToBeats(6_000, 128f))
        // An unknown tempo, or less than one beat asked for, changes nothing.
        assertEquals(6_000L, snapMixToBeats(6_000, 0f))
        assertEquals(200L, snapMixToBeats(200, 120f))
        // Whatever the tempo, the length never moves by more than one beat.
        for (bpm in 60..200 step 5) for (ms in 2_000L..20_000L step 500) {
            val beat = 60_000f / bpm
            assertTrue("$bpm $ms", kotlin.math.abs(snapMixToBeats(ms, bpm.toFloat()) - ms) <= beat + 1f)
        }
    }

    @Test fun theArrivingTrackIsMovedOntoTheLeavingOnesBeats() {
        val beat = 500f // 120 BPM
        // The leaving track is exactly on a beat; the arriving one must be too.
        val start = alignedStart(10_000, 0, 120f, 1f, 4_000, 0, 120f, 1f)
        assertEquals(0f, (start % beat.toLong()).toFloat(), 1f)
        // Half a beat left before the leaving track's next beat: the arriving one starts half a beat in.
        val half = alignedStart(10_000, 0, 120f, 1f, 4_250, 0, 120f, 1f)
        assertEquals(250f, (half % beat.toLong()).toFloat(), 1f)
        // The move stays under half a beat, whatever the grids.
        for (offset in 0L..900L step 50) for (position in 3_000L..5_000L step 250) {
            val aligned = alignedStart(10_000, offset, 120f, 1f, position, 120, 120f, 1f)
            assertTrue("$offset $position -> $aligned", kotlin.math.abs(aligned - 10_000) <= beat / 2 + 1)
        }
        // An unknown tempo on either side leaves the asked-for position alone.
        assertEquals(10_000L, alignedStart(10_000, 0, 0f, 1f, 4_000, 0, 120f, 1f))
        assertEquals(10_000L, alignedStart(10_000, 0, 120f, 1f, 4_000, 0, 0f, 1f))
    }

    @Test fun smartOrderChainsTheClosestTempoRatherThanAnyTrack() {
        val tracks = listOf(
            TrackProfile("fast", 128f, "A", "house"),
            TrackProfile("slow", 90f, "B", "hip-hop"),
            TrackProfile("near", 126f, "C", "house"),
            TrackProfile("slow2", 92f, "B", "hip-hop"))
        val order = smartOrder(tracks, tracks[0], seed = 1)
        assertEquals(tracks.map { it.uri }.toSet(), order.map { it.uri }.toSet())
        assertEquals("fast", order[0].uri)
        // 126 follows 128; the two slow ones stay together at the far end.
        assertEquals("near", order[1].uri)
        assertEquals(setOf("slow", "slow2"), order.drop(2).map { it.uri }.toSet())
    }

    @Test fun smartOrderStillWorksBeforeAnythingHasBeenAnalysed() {
        val tracks = (1..6).map { TrackProfile("t$it", 0f, "Artiste $it") }
        val order = smartOrder(tracks, seed = 7)
        assertEquals(6, order.size)
        assertEquals(tracks.map { it.uri }.toSet(), order.map { it.uri }.toSet())
    }

    @Test fun theSameArtistIsPushedAwayFromItself() {
        val same = TrackProfile("a2", 120f, "Meme", "rock")
        val other = TrackProfile("b1", 124f, "Autre", "rock")
        val current = TrackProfile("a1", 120f, "Meme", "rock")
        // An exact tempo match is not worth hearing the same artist twice in a row.
        assertTrue(transitionCost(current, other) < transitionCost(current, same))
    }

    @Test fun everyFadeProfileStaysBoundedAndKeepsItsLoudness() {
        for(style in listOf("linear","smooth","cut","club","sweep"))for(i in -10..110){val gains=shapedMix(i/100f,style)
            assertTrue(gains.incoming in 0f..1f);assertTrue(gains.outgoing in 0f..1f);assertEquals(1f,gains.power,.0001f)
        }
        assertEquals(MixGains(1f,0f),shapedMix(0f,"smooth"));assertEquals(MixGains(0f,1f),shapedMix(1f,"smooth"))
    }
}
