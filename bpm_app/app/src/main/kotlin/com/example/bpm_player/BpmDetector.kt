package com.example.bpm_player

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detector de BPM em Kotlin puro.
 *
 * Estratégia:
 *  1. Calcular envelope de energia (frames curtos, hop curto).
 *  2. Decimar o envelope (~5x) para reduzir variância da autocorrelação
 *     e aproximar a banda rítmica típica (~5–10 envelopes/s).
 *  3. Calcular autocorrelação normalizada por lag (Pearson) para eliminar
 *     o viés de "lags grandes sempre terem mais amostras".
 *  4. Buscar o pico dentro de 60..200 BPM, e ao redor do pico fazer
 *     interpolação parabólica para obter resolução de fração de BPM.
 *  5. Aplicar correção de oitava: se o pico real estiver perto do
 *     dobro / metade de um BPM musicalmente comum, ajustar.
 */
object BpmDetector {

    data class Result(val bpm: Float, val confidence: Float)

    private const val MIN_BPM = 60f
    private const val MAX_BPM = 200f
    private const val DEFAULT_BPM = 120f

    private const val FRAME_SIZE = 1024
    private const val HOP = 512

    private const val SILENCE_RMS = 0.01f
    private const val MIN_CONFIDENCE = 0.25f

    // Decimação do envelope. Com HOP=512 @44.1k, frame rate ≈ 86Hz.
    // Decimação 8 → ≈10.7Hz, o que dá lags confortáveis para 60..200 BPM:
    //   60 BPM  → 0.5  s/beat → 10.7 * 0.5  ≈ 5.4  frames/beat
    //  200 BPM  → 0.3  s/beat → 10.7 * 0.3  ≈ 3.2  frames/beat
    // Mantém o lag mínimo acima de 2 (evita pico em lag=2 que daria ~214 BPM).
    private const val ENV_DECIMATION = 8

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

        // Envelope de energia bruta
        val numFrames = (samples.size - FRAME_SIZE) / HOP
        if (numFrames < 32) return Result(DEFAULT_BPM, 0f)

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

        // Decimação por média — reduz ruído e estabiliza autocorrelação
        val decim = ENV_DECIMATION
        val decimNum = numFrames / decim
        if (decimNum < 16) return Result(DEFAULT_BPM, 0f)
        val envDec = FloatArray(decimNum)
        for (f in 0 until decimNum) {
            var sum = 0f
            for (k in 0 until decim) sum += envelope[f * decim + k]
            envDec[f] = sum / decim
        }

        // Remove a média
        var mean = 0f
        for (e in envDec) mean += e
        mean /= decimNum
        for (i in envDec.indices) envDec[i] -= mean

        // Pré-calcula energia cumulativa para normalização por lag
        val cumSq = FloatArray(decimNum + 1)
        for (i in 0 until decimNum) cumSq[i + 1] = cumSq[i] + envDec[i] * envDec[i]
        val totalSq = cumSq[decimNum]
        if (totalSq <= 0f) return Result(DEFAULT_BPM, 0f)

        // Taxa efetiva do envelope decimado
        val envRate = sampleRate.toFloat() / (HOP * decim)

        // Lags em frames decimados
        val minLag = (60f / MAX_BPM * envRate).roundToInt().coerceAtLeast(2)
        val maxLag = (60f / MIN_BPM * envRate).roundToInt().coerceAtMost(decimNum - 1)
        if (maxLag <= minLag) return Result(DEFAULT_BPM, 0f)

        // Autocorrelação normalizada por Pearson — divide pela média geométrica
        // da energia do segmento, eliminando o viés de "lags grandes = mais energia".
        var bestCorr = 0f
        var bestLag = 0
        val corrs = FloatArray(maxLag + 1)  // para interpolação depois
        for (lag in minLag..maxLag) {
            var sum = 0f
            val limit = decimNum - lag
            for (i in 0 until limit) sum += envDec[i] * envDec[i + lag]
            val e1 = cumSq[limit]
            val e2 = cumSq[decimNum] - cumSq[lag]
            val denom = sqrt((e1 * e2).toDouble()).toFloat()
            val corr = if (denom > 0f) sum / denom else 0f
            corrs[lag] = corr
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        if (bestLag <= 0 || bestCorr < MIN_CONFIDENCE) {
            return Result(DEFAULT_BPM, bestCorr)
        }

        // Interpolação parabólica em torno do pico para resolução fracionária
        val c0 = if (bestLag > minLag) corrs[bestLag - 1] else corrs[bestLag]
        val c1 = corrs[bestLag]
        val c2 = if (bestLag < maxLag) corrs[bestLag + 1] else corrs[bestLag]
        val denom = (c0 - 2f * c1 + c2)
        val delta = if (denom != 0f) 0.5f * (c0 - c2) / denom else 0f
        val refinedLag = (bestLag + delta).coerceAtLeast(minLag.toFloat())
        val rawBpm = 60f * envRate / refinedLag

        // Correção de oitava: se o pico estiver perto de 2x um BPM
        // musicalmente comum na metade inferior, e o pico tiver confiança
        // comparável ali, prefira a versão dobrada (mais confiável).
        val bpm = refineOctave(rawBpm, corrs, bestLag, minLag, maxLag, envRate)

        return Result(bpm.coerceIn(MIN_BPM, MAX_BPM), bestCorr)
    }

    /**
     * Se o BPM detectado for > 160 (perto de 200), verifica se a metade do BPM
     * também tem pico forte na autocorrelação. Se tiver, prefere a metade,
     * pois BPMs muito altos são raros em música popular.
     */
    private fun refineOctave(
        rawBpm: Float,
        corrs: FloatArray,
        bestLag: Int,
        minLag: Int,
        maxLag: Int,
        envRate: Float
    ): Float {
        if (rawBpm <= 160f) return rawBpm

        // BPM metade = BPM/2 → lag dobrado
        val halfLag = (bestLag * 2).coerceAtMost(maxLag)
        if (halfLag > maxLag) return rawBpm
        val halfCorr = corrs[halfLag]

        // Se a autocorrelação no dobro do lag for forte (>= 70% do pico),
        // provavelmente a verdade é a metade (BPM mais baixo, mais comum).
        return if (halfCorr >= 0.7f * corrs[bestLag]) {
            60f * envRate / halfLag
        } else {
            rawBpm
        }
    }
}
