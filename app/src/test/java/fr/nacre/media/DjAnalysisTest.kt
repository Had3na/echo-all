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
        assertEquals(1.1f,matchedSpeed(120f,1.1f,120f)!!,.0001f)
        assertEquals(1.2f,matchedSpeed(120f,1f,100f)!!,.0001f)
        assertNull(matchedSpeed(180f,1f,80f));assertNull(matchedSpeed(0f,1f,100f))
    }
    @Test fun everyFadeProfileStaysBoundedAndConstantSum() {
        for(style in listOf("linear","smooth","cut"))for(i in -10..110){val gains=shapedMix(i/100f,style)
            assertTrue(gains.incoming in 0f..1f);assertTrue(gains.outgoing in 0f..1f);assertEquals(1f,gains.incoming+gains.outgoing,.0001f)
        }
        assertEquals(MixGains(1f,0f),shapedMix(0f,"smooth"));assertEquals(MixGains(0f,1f),shapedMix(1f,"smooth"))
    }
}
