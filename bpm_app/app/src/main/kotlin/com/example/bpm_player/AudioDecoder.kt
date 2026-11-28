package com.example.bpm_player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteOrder

/**
 * Decodifica um arquivo de áudio (MP3, AAC, etc.) para PCM mono em float,
 * usando MediaExtractor + MediaCodec — sem NDK, sem bibliotecas externas.
 *
 * Decodifica no máximo [maxDurationUs] microssegundos; em faixas longas começa em
 * [SKIP_INTRO_US] para pular introduções sem bateria, que derrubam a detecção de BPM.
 */
    object AudioDecoder {

        data class PcmData(val samples: FloatArray, val sampleRate: Int)

        private const val TIMEOUT_US = 10_000L
        private const val DEFAULT_MAX_DURATION_US = 20_000_000L // 20 s — suficiente para BPM
        private const val SKIP_INTRO_US = 30_000_000L

    fun decode(
        context: Context,
        uri: Uri,
        maxDurationUs: Long = DEFAULT_MAX_DURATION_US
    ): PcmData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            // Localiza a primeira trilha de áudio
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = format
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) return null

            extractor.selectTrack(trackIndex)
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = if (maxDurationUs > 0L && durationUs >= SKIP_INTRO_US + maxDurationUs) SKIP_INTRO_US else 0L
            if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val endUs = startUs + maxDurationUs

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            // FloatArray que cresce: ArrayList<Float> criava um objeto por amostra (GC travava a análise)
            var pcm = FloatArray(if (maxDurationUs > 0L) (maxDurationUs / 1_000_000L * sampleRate + sampleRate).toInt() else sampleRate * 60)
            var pcmCount = 0
            var inputDone = false
            var outputDone = false
            val decodeAll = maxDurationUs <= 0L

            while (!outputDone) {
                // Alimenta o decoder com dados comprimidos
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || (!decodeAll && extractor.sampleTime > endUs)) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drena o PCM decodificado
                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // sem dados no momento, tenta de novo
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex) ?: continue
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)

                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    while (buffer.remaining() >= 4 * channels) {
                                        var sum = 0f
                                        repeat(channels) { sum += buffer.float }
                                        if (pcmCount == pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                                        pcm[pcmCount++] = sum / channels
                                    }
                                }
                                else -> { // ENCODING_PCM_16BIT
                                    while (buffer.remaining() >= 2 * channels) {
                                        var sum = 0
                                        repeat(channels) { sum += buffer.short.toInt() }
                                        if (pcmCount == pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                                        pcm[pcmCount++] = sum / (32768f * channels)
                                    }
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            if (pcmCount == 0) return null
            return PcmData(pcm.copyOf(pcmCount), sampleRate)
        } catch (e: Exception) {
            android.util.Log.e("BPM_DECODE", "decode failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
