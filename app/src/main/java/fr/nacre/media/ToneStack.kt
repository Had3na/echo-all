package fr.nacre.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

val EQ_CENTERS = floatArrayOf(60f, 250f, 1_000f, 4_000f, 16_000f)
private const val BASS_SHELF_HZ = 220.0
private const val LOW_PASS_OFF_HZ = 18_000.0

/** RBJ cookbook biquad in transposed direct form II, one state pair per channel. */
class Biquad(channels: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(channels); private val z2 = DoubleArray(channels)
    var enabled = false; private set

    fun off() { enabled = false }
    fun clear() { z1.fill(0.0); z2.fill(0.0) }

    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        // A filter switched back on starts from silence; near-neutral settings make that seamless.
        if (!enabled) clear()
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
        enabled = true
    }

    fun peaking(hz: Double, db: Double, q: Double, rate: Int) {
        if (abs(db) < .05 || hz >= .45 * rate) return off()
        val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(hz: Double, db: Double, rate: Int) {
        if (abs(db) < .05) return off()
        val a = 10.0.pow(db / 40); val w = 2 * PI * hz / rate; val c = cos(w); val beta = sqrt(2 * a) * sin(w) // 2·√A·alpha with shelf slope S = 1
        set(a * ((a + 1) - (a - 1) * c + beta), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - beta),
            (a + 1) + (a - 1) * c + beta, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - beta)
    }

    fun lowPass(hz: Double, q: Double, rate: Int) {
        val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun highPass(hz: Double, q: Double, rate: Int) {
        val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * q); val c = cos(w)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun process(x: Double, channel: Int): Double {
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y
    }
}

/** The user's 5-band equalizer followed by the transition filters, computed in the app so every phone sounds the same. */
class ToneStack(private val rate: Int, channels: Int) {
    private val bands = Array(EQ_CENTERS.size) { Biquad(channels) }
    private val bass = Biquad(channels)
    private val highPass = Biquad(channels)
    private val lowPass = Biquad(channels)
    private val all = bands + arrayOf(bass, highPass, lowPass)
    private var active = emptyArray<Biquad>()
    val bypass get() = active.isEmpty()

    fun configure(eq: IntArray, shape: SoundShape) {
        EQ_CENTERS.forEachIndexed { i, hz -> bands[i].peaking(hz.toDouble(), eq.getOrElse(i) { 0 }.coerceIn(-12, 12).toDouble(), .7, rate) }
        bass.lowShelf(BASS_SHELF_HZ, shape.bassDb.toDouble(), rate)
        if (shape.highPassHz > 20f && shape.highPassHz < .45f * rate) highPass.highPass(shape.highPassHz.toDouble(), .9, rate) else highPass.off()
        if (shape.lowPassHz > 20f && shape.lowPassHz < minOf(LOW_PASS_OFF_HZ, .45 * rate)) lowPass.lowPass(shape.lowPassHz.toDouble(), .9, rate) else lowPass.off()
        active = all.filter { it.enabled }.toTypedArray()
    }

    fun clear() { all.forEach { it.clear() } }

    fun process(x: Double, channel: Int): Double {
        var y = x
        for (stage in active) y = stage.process(y, channel)
        return y
    }
}
