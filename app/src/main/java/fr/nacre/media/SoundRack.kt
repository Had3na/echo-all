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
import kotlin.math.roundToInt

/** PCM stage inserted in each deck's audio sink. Settings are written from the main thread and picked up per buffer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SoundChain : BaseAudioProcessor() {
    @Volatile var eq = IntArray(EQ_CENTERS.size)
    @Volatile var shape = SoundShape.NEUTRAL
    private var tones: ToneStack? = null
    private var appliedEq: IntArray? = null
    private var appliedShape: SoundShape? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush() { tones = null; appliedEq = null; appliedShape = null }
    override fun onReset() { onFlush() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val format = inputAudioFormat
        // Decoded PCM is in native order; make sure samples are read that way whatever buffer we are handed.
        inputBuffer.order(ByteOrder.nativeOrder())
        val stack = tones ?: ToneStack(format.sampleRate, format.channelCount).also { tones = it }
        val eqNow = eq; val shapeNow = shape
        if (eqNow !== appliedEq || shapeNow != appliedShape) { stack.configure(eqNow, shapeNow); appliedEq = eqNow; appliedShape = shapeNow }
        val output = replaceOutputBuffer(size)
        val channels = format.channelCount
        var channel = 0
        when {
            stack.bypass -> output.put(inputBuffer)
            format.encoding == C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= 2) {
                val y = stack.process(inputBuffer.getShort() / 32768.0, channel)
                output.putShort((y * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
                channel = (channel + 1) % channels
            }
            else -> while (inputBuffer.remaining() >= 4) {
                output.putFloat(stack.process(inputBuffer.getFloat().toDouble(), channel).toFloat().coerceIn(-1f, 1f))
                channel = (channel + 1) % channels
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}

/** Owns the sound chain of every deck: user equalizer plus Club/Filtre transition shapes. */
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
}
