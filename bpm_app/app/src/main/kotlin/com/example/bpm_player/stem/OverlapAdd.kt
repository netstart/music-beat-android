package com.example.bpm_player.stem

/**
 * Junta as janelas do modelo com peso triangular (como o `apply_model` do Demucs),
 * guardando só uma janela de memória: cada [push] devolve os quadros que já não mudam mais.
 *
 * Entrada de [push]: predição canal-maior [ch][seg]. Saída: quadros intercalados [t][ch].
 */
class OverlapAdd(private val channels: Int, private val seg: Int, val stride: Int) {

    private val weight = FloatArray(seg) { t -> minOf(t + 1, seg - t).toFloat() }
    private val acc = FloatArray(channels * seg)
    private val wsum = FloatArray(seg)

    /** Soma a janela e emite os primeiros [count] quadros (stride, ou o resto da música na última janela). */
    fun push(pred: FloatArray, count: Int, emit: (frames: FloatArray, count: Int) -> Unit) {
        for (c in 0 until channels) {
            val base = c * seg
            for (t in 0 until seg) acc[base + t] += weight[t] * pred[base + t]
        }
        for (t in 0 until seg) wsum[t] += weight[t]

        val out = FloatArray(count * channels)
        for (t in 0 until count) {
            val w = wsum[t]
            for (c in 0 until channels) out[t * channels + c] = acc[c * seg + t] / w
        }
        emit(out, count)

        // Desliza a janela para o próximo offset.
        for (c in 0 until channels) {
            val base = c * seg
            System.arraycopy(acc, base + stride, acc, base, seg - stride)
            java.util.Arrays.fill(acc, base + seg - stride, base + seg, 0f)
        }
        System.arraycopy(wsum, stride, wsum, 0, seg - stride)
        java.util.Arrays.fill(wsum, seg - stride, seg, 0f)
    }
}
