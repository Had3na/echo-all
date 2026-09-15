package fr.nacre.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

data class LoudnessReading(val id: String, val lufs: Double, val seconds: Double)

/**
 * PCM stage inserted in each deck's audio sink: loudness measurement of the untouched signal, user EQ and
 * transition filters, normalization gain, then a peak limiter. Settings are written from the main thread.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoundChain : BaseAudioProcessor() {
    @Volatile var eq = IntArray(EQ_CENTERS.size)
    @Volatile var shape = SoundShape.NEUTRAL
    @Volatile var levelDb = 0f
    /** Media id whose loudness is being measured; null stops measuring. */
    @Volatile var measureId: String? = null
    @Volatile var reading: LoudnessReading? = null; private set

    private var tones: ToneStack? = null
    private var limiter: Limiter? = null
    private var appliedEq: IntArray? = null
    private var appliedShape: SoundShape? = null
    private var level = 1.0
    private var meter: LoudnessMeter? = null
    private var meterId: String? = null
    private var publishedBlocks = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush() { tones = null; limiter = null; appliedEq = null; appliedShape = null }
    override fun onReset() { onFlush(); meter = null; meterId = null }

    private fun publish() {
        val m = meter ?: return
        val id = meterId ?: return
        m.integrated()?.let { reading = LoudnessReading(id, it, m.seconds) }
        publishedBlocks = m.seconds
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val bytes = if (float) 4 else 2
        // Decoded PCM is in native order; make sure samples are read that way whatever buffer we are handed.
        inputBuffer.order(ByteOrder.nativeOrder())

        val stack = tones ?: ToneStack(format.sampleRate, channels).also { tones = it }
        val eqNow = eq; val shapeNow = shape
        if (eqNow !== appliedEq || shapeNow != appliedShape) { stack.configure(eqNow, shapeNow); appliedEq = eqNow; appliedShape = shapeNow }
        val peakLimiter = limiter ?: Limiter(format.sampleRate).also { limiter = it }

        val id = measureId
        if (id != meterId || (meter != null && meter?.rate != format.sampleRate)) {
            if (id != meterId) publish()
            meter = id?.let { LoudnessMeter(format.sampleRate, channels) }; meterId = id; publishedBlocks = 0.0
        }
        val loudness = meter
        val targetLevel = 10.0.pow(levelDb / 20.0)
        // About 20 ms to reach a new level: no click when a track's normalization changes.
        val smoothing = 1 - exp(-1.0 / (.02 * format.sampleRate))

        fun read(): Double = if (float) inputBuffer.getFloat().toDouble() else inputBuffer.getShort() / 32768.0
        val output = replaceOutputBuffer(size)
        if (stack.bypass && abs(level - 1.0) < 1e-6 && abs(targetLevel - 1.0) < 1e-6) {
            // Nothing to change: measure, then pass the bytes through untouched.
            if (loudness != null) {
                val start = inputBuffer.position()
                while (inputBuffer.remaining() >= bytes * channels) { repeat(channels) { loudness.add(read(), it) }; loudness.endFrame() }
                inputBuffer.position(start)
            }
            output.put(inputBuffer)
        } else {
            val frame = DoubleArray(channels)
            while (inputBuffer.remaining() >= bytes * channels) {
                level += (targetLevel - level) * smoothing
                var peak = 0.0
                for (channel in 0 until channels) {
                    val x = read()
                    loudness?.add(x, channel)
                    val y = stack.process(x, channel) * level
                    frame[channel] = y
                    if (abs(y) > peak) peak = abs(y)
                }
                loudness?.endFrame()
                val gain = peakLimiter.gainFor(peak)
                for (channel in 0 until channels) {
                    val y = frame[channel] * gain
                    if (float) output.putFloat(y.toFloat()) else output.putShort((y * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
        if (loudness != null && loudness.seconds - publishedBlocks >= 10) publish()
    }
}

/** Owns the sound chain of every deck: user equalizer, Club/Filtre shapes, volume normalization and loudness readings. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoundRack(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val chains = HashMap<ExoPlayer, SoundChain>()
    private var levels = readLevels()

    private fun readLevels() = IntArray(EQ_CENTERS.size) { prefs.getInt("eq$it", 0) }

    fun renderers(chain: SoundChain): RenderersFactory = object : DefaultRenderersFactory(app) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(arrayOf(chain)).build()
    }
    fun attach(player: ExoPlayer, chain: SoundChain) { chain.eq = levels; chains[player] = chain }
    fun detach(player: ExoPlayer) { chains.remove(player) }
    fun update() {
        val next = readLevels()
        if (!next.contentEquals(levels)) { levels = next; chains.values.forEach { it.eq = next } }
    }
    fun shape(player: ExoPlayer, shape: SoundShape) { chains[player]?.shape = shape }
    fun level(player: ExoPlayer, db: Float) { chains[player]?.levelDb = db }
    fun measure(player: ExoPlayer, id: String?) { chains[player]?.measureId = id }
    fun reading(player: ExoPlayer): LoudnessReading? = chains[player]?.reading
}
