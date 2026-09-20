package fr.nacre.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.sqrt

// Decode at most 90 seconds locally. No media is uploaded or played during analysis.
suspend fun analyzeAudio(context: Context, uri: String): TrackTools = withContext(Dispatchers.IO) {
    require(Uri.parse(uri).scheme == "content") { "Analyse disponible pour les fichiers locaux." }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, Uri.parse(uri), null)
        val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("Aucune piste audio détectée.")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0); decoder.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var inputEnd = false; var outputEnd = false
        var sum = 0.0; var samples = 0
        val energy = mutableListOf<Float>()
        // Level, read in the same pass rather than waiting for the track to have been played
        // once: an unmeasured track arrives in a transition at whatever level it was mastered.
        var meter: LoudnessMeter? = null
        var channel = 0
        val info = MediaCodec.BufferInfo()
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
        while (!outputEnd && energy.size < 9000) {
            ensureActive()
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Analyse trop longue. Utilise le bouton TAP." }
            if (!inputEnd) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > 90_000_000) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true
                    } else { decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            when (val out = decoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Format audio non analysable." }
                }
                else -> if (out >= 0) {
                    try {
                        val buffer = decoder.getOutputBuffer(out)!!.duplicate().order(ByteOrder.nativeOrder())
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val window = (rate * channels / 100).coerceAtLeast(1)
                        val level = meter ?: LoudnessMeter(rate, channels).also { meter = it }
                        while (buffer.remaining() >= bytes) {
                            val value = if (bytes == 4) buffer.float else buffer.short / 32768f
                            sum += value * value; samples++
                            level.add(value.toDouble(), channel)
                            if (++channel >= channels) { channel = 0; level.endFrame() }
                            if (samples >= window) { energy += sqrt(sum / samples).toFloat(); samples = 0; sum = 0.0 }
                        }
                        outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(out, false) }
                }
            }
        }
        val tempo = estimateTempo(energy)
        val maximum = (energy.maxOrNull() ?: 1f).coerceAtLeast(.0001f)
        val wave = if (energy.isEmpty()) emptyList() else List(128) { i ->
            val from = i * energy.size / 128; val to = ((i + 1) * energy.size / 128).coerceAtLeast(from + 1).coerceAtMost(energy.size)
            energy.subList(from.coerceAtMost(energy.lastIndex), to).maxOrNull()!! / maximum
        }
        val store = StudioStore(context)
        // Only when nothing better is known: a figure gathered over a whole play beats 90 seconds.
        if (store.loudness(uri) == null) meter?.integrated()?.let { store.saveLoudness(uri, it, meter?.seconds ?: 0.0) }
        TrackTools(bpm = tempo.bpm, confidence = tempo.confidence, wave = wave,
            beatMs = beatPhaseMs(energy, tempo.bpm))
    } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
}
