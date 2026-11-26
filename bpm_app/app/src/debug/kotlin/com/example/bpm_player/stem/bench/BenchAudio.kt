package com.example.bpm_player.stem.bench

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder

/**
 * Decodificador estéreo mínimo para o benchmark (o AudioDecoder do app faz downmix p/ mono,
 * e o HTDemucs precisa do estéreo). Retorna canais separados, reamostrados para 44,1 kHz.
 */
object BenchAudio {

    const val TARGET_RATE = 44_100

    class Stereo(val left: FloatArray, val right: FloatArray) {
        val frames: Int get() = left.size
    }

    fun decodeAssetStereo(context: Context, assetPath: String, startUs: Long, durationUs: Long): Stereo {
        val extractor = MediaExtractor()
        val afd = context.assets.openFd(assetPath)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val l = FloatList()
            val r = FloatList()
            val info = MediaCodec.BufferInfo()
            val endUs = startUs + durationUs
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
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
                    val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    while (buf.remaining() >= bytes * channels) {
                        val a = if (bytes == 4) buf.float else buf.short / 32768f
                        val b = if (channels >= 2) (if (bytes == 4) buf.float else buf.short / 32768f) else a
                        repeat(maxOf(0, channels - 2)) { if (bytes == 4) buf.float else buf.short }
                        l.add(a); r.add(b)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            return Stereo(resample(l.toArray(), rate), resample(r.toArray(), rate))
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
            afd.close()
        }
    }

    /** Linear: suficiente para medir desempenho; a Fase 1 usará um resampler de qualidade. */
    private fun resample(x: FloatArray, rate: Int): FloatArray {
        if (rate == TARGET_RATE || x.isEmpty()) return x
        val ratio = rate.toDouble() / TARGET_RATE
        val n = (x.size / ratio).toInt()
        return FloatArray(n) { i ->
            val p = i * ratio
            val k = p.toInt().coerceAtMost(x.size - 1)
            val k1 = (k + 1).coerceAtMost(x.size - 1)
            val t = (p - k).toFloat()
            x[k] * (1f - t) + x[k1] * t
        }
    }

    private class FloatList {
        private var a = FloatArray(1 shl 16)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }
}
