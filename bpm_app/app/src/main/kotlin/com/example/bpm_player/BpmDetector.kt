package com.example.bpm_player

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de BPM em Kotlin puro.
 *
 * Estratégia: envelope de energia (frames de 1024, hop 512) + autocorrelação
 * do envelope nos lags correspondentes a 60..200 BPM. O lag com maior
 * correlação vira o BPM estimado.
 *
 * Substitui a versão em C++ (autocorrelação de áudio bruto), que tinha o bug
 * de nunca testar lags maiores que o tamanho do frame.
 */
object BpmDetector {

    data class Result(val bpm: Float, val confidence: Float)

    private const val MIN_BPM = 60f
    private const val MAX_BPM = 200f
    private const val DEFAULT_BPM = 120f

    private const val FRAME_SIZE = 1024
    private const val HOP = 512

    private const val SILENCE_RMS = 0.01f
    private const val MIN_CONFIDENCE = 0.1f

    /**
     * @param samples PCM mono, floats no intervalo [-1, 1]
     * @param sampleRate taxa de amostragem (ex.: 44100)
     */
    fun detect(samples: FloatArray, sampleRate: Int): Result {
        if (samples.size < sampleRate) return Result(DEFAULT_BPM, 0f)

        // RMS — áudio muito baixo não vale a pena analisar
        var acc = 0.0
        for (s in samples) acc += s * s
        val rms = sqrt(acc / samples.size).toFloat()
        if (rms < SILENCE_RMS) return Result(DEFAULT_BPM, 0f)

        // Envelope de energia
        val numFrames = (samples.size - FRAME_SIZE) / HOP
        if (numFrames < 16) return Result(DEFAULT_BPM, 0f)

        val envelope = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            var energy = 0f
            val offset = f * HOP
            for (i in 0 until FRAME_SIZE) {
                val s = samples[offset + i]
                energy += s * s
            }
            envelope[f] = energy / FRAME_SIZE
        }

        // Remove a média para a autocorrelação não ficar enviesada
        var mean = 0f
        for (e in envelope) mean += e
        mean /= numFrames
        for (i in envelope.indices) envelope[i] -= mean

        var norm = 0f
        for (e in envelope) norm += e * e
        if (norm <= 0f) return Result(DEFAULT_BPM, 0f)

        // Lags em frames correspondentes ao intervalo de BPM
        val minLag = (60f * sampleRate / (HOP * MAX_BPM)).roundToInt().coerceAtLeast(1)
        val maxLag = (60f * sampleRate / (HOP * MIN_BPM)).roundToInt().coerceAtMost(numFrames - 1)
        if (maxLag <= minLag) return Result(DEFAULT_BPM, 0f)

        var bestCorr = 0f
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var corr = 0f
            val limit = numFrames - lag
            for (i in 0 until limit) {
                corr += envelope[i] * envelope[i + lag]
            }
            corr /= norm
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        if (bestLag == 0 || bestCorr < MIN_CONFIDENCE) return Result(DEFAULT_BPM, 0f)

        val bpm = 60f * sampleRate / (HOP * bestLag)
        return Result(bpm.coerceIn(MIN_BPM, MAX_BPM), bestCorr)
    }
}
