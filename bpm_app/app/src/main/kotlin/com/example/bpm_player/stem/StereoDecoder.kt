package com.example.bpm_player.stem

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteOrder

/**
 * Decodifica a música inteira em PCM 16-bit estéreo intercalado (L,R,L,R…), na taxa original.
 * O HTDemucs precisa do estéreo — o AudioDecoder do app faz downmix p/ mono (serve só ao BPM).
 * 4 min @ 48 kHz ≈ 46 MB em ShortArray (metade de float).
 */
class StereoPcm(val data: ShortArray, val frames: Int, val sampleRate: Int) {

    /** Número de quadros após reamostrar para [rate]. */
    fun framesAt(rate: Int): Int = (frames.toLong() * rate / sampleRate).toInt()

    /** Amostra do canal [ch] no quadro [i] da taxa [rate] (interpolação linear). */
    fun sampleAt(ch: Int, i: Int, rate: Int): Float {
        if (rate == sampleRate) return data[i * 2 + ch] / 32768f
        // shortcut: reamostragem linear (perde um pouco de agudo em 48→44,1 kHz); trocar por polifásica se a qualidade incomodar
        val p = i.toDouble() * sampleRate / rate
        val k = p.toInt().coerceAtMost(frames - 1)
        val k1 = (k + 1).coerceAtMost(frames - 1)
        val t = (p - k).toFloat()
        return (data[k * 2 + ch] * (1f - t) + data[k1 * 2 + ch] * t) / 32768f
    }
}

object StereoDecoder {

    fun decode(context: Context, uri: Uri, isCancelled: () -> Boolean): StereoPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("arquivo sem trilha de áudio")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // Pré-aloca pela duração para não dobrar a memória com realocações.
            var out = ShortArray(((durUs * rate / 1_000_000L + rate) * 2).toInt().coerceAtLeast(rate * 2))
            var n = 0

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (isCancelled()) throw InterruptedException()
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    val bytes = if (isFloat) 4 else 2
                    while (buf.remaining() >= bytes * channels) {
                        if (n + 2 > out.size) out = out.copyOf(out.size + out.size / 2)
                        for (c in 0 until channels) {
                            val s = if (isFloat) (buf.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort() else buf.short
                            if (c < 2) out[n + c] = s
                        }
                        if (channels == 1) out[n + 1] = out[n] // mono → estéreo
                        n += 2
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            require(n > 0) { "nenhum áudio decodificado" }
            return StereoPcm(out, n / 2, rate)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
