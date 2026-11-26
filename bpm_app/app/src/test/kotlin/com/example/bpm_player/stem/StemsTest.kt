package com.example.bpm_player.stem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StemsTest {

    /** Se o "modelo" devolve a própria janela, o overlap-add tem que reconstruir o sinal exatamente. */
    @Test
    fun overlapAddReconstructsIdentity() {
        val ch = 3; val seg = 16; val stride = 12; val total = 50
        val signal = Array(ch) { c -> FloatArray(total) { t -> (c + 1) * 100f + t } }
        val ola = OverlapAdd(ch, seg, stride)
        val got = ArrayList<Float>()
        var offset = 0
        while (offset < total) {
            val pred = FloatArray(ch * seg) { i -> val c = i / seg; val t = offset + i % seg; if (t < total) signal[c][t] else 0f }
            ola.push(pred, minOf(stride, total - offset)) { f, n -> for (i in 0 until n * ch) got += f[i] }
            offset += stride
        }
        val expected = FloatArray(total * ch) { i -> signal[i % ch][i / ch] }
        assertArrayEquals(expected, got.toFloatArray(), 1e-3f)
    }

    @Test
    fun gainsHonourSoloThenMute() {
        val all = effectiveGains(listOf(StemControl(), StemControl(muted = true), StemControl(volume = 0.5f), StemControl()))
        assertArrayEquals(floatArrayOf(1f, 0f, 0.5f, 1f), all, 0f)
        val solo = effectiveGains(listOf(StemControl(), StemControl(solo = true, volume = 0.8f), StemControl(), StemControl(solo = true, muted = true)))
        assertArrayEquals(floatArrayOf(0f, 0.8f, 0f, 1f), solo, 0f) // solo vence o mudo do próprio stem
    }

    @Test
    fun mixerSumsStemsIntoStereoAndClips() {
        val frames = 2
        val input = ByteBuffer.allocate(frames * 8 * 2).order(ByteOrder.nativeOrder())
        // quadro 0: cada stem L=1000, R=-1000 · quadro 1: tudo no máximo (deve saturar)
        repeat(4) { input.putShort(1000); input.putShort(-1000) }
        repeat(8) { input.putShort(32767) }
        input.flip()
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        StemMixer.mix(input, out, frames, floatArrayOf(1f, 0f, 0.5f, 1f))
        out.flip()
        assertEquals(2500, out.short.toInt())
        assertEquals(-2500, out.short.toInt())
        assertEquals(32767, out.short.toInt())
        assertEquals(32767, out.short.toInt())
    }
}
