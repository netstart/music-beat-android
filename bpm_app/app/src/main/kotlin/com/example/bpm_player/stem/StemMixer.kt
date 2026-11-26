package com.example.bpm_player.stem

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Estado de um stem na mesa: volume 0..1, mudo e solo. */
data class StemControl(val volume: Float = 1f, val muted: Boolean = false, val solo: Boolean = false)

/** Ganho efetivo: se algum stem está em solo, só os solados tocam; senão, todos menos os mudos. */
fun effectiveGains(controls: List<StemControl>): FloatArray {
    val anySolo = controls.any { it.solo }
    return FloatArray(controls.size) { i ->
        val s = controls[i]
        when {
            anySolo -> if (s.solo) s.volume else 0f
            s.muted -> 0f
            else -> s.volume
        }
    }
}

/**
 * Mixa o WAV de stems (8 canais = 4 stems × estéreo, 16-bit) em estéreo, antes do Sonic do ExoPlayer.
 * Um player só ⇒ sincronia exata, seek normal e o BPM (velocidade) continua valendo.
 * Para qualquer outro formato fica inativo e o áudio passa intacto.
 */
@UnstableApi
class StemMixer : BaseAudioProcessor() {

    @Volatile
    var gains: FloatArray = FloatArray(STEMS) { 1f }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.channelCount != STEMS * 2 || inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val g = gains
        val frames = inputBuffer.remaining() / (STEMS * 2 * 2)
        val out = replaceOutputBuffer(frames * 4)
        mix(inputBuffer, out, frames, g)
        out.flip()
    }

    companion object {
        const val STEMS = 4

        /** Núcleo do mix, separado para teste: lê [frames] quadros de 8 canais e escreve 2 canais. */
        fun mix(input: ByteBuffer, out: ByteBuffer, frames: Int, g: FloatArray) {
            input.order(ByteOrder.nativeOrder())
            out.order(ByteOrder.nativeOrder())
            repeat(frames) {
                var l = 0f
                var r = 0f
                for (s in 0 until STEMS) {
                    l += input.short * g[s]
                    r += input.short * g[s]
                }
                out.putShort(l.coerceIn(-32768f, 32767f).toInt().toShort())
                out.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
    }
}
