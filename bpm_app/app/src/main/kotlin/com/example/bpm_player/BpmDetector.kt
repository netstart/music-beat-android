package com.example.bpm_player

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detector de BPM em Kotlin puro.
 *
 * 1. Onset: fluxo espectral até 2 kHz (FFT 1024, hop 256) com compressão log — marca
 *    ataques de bumbo, caixa e baixo em vez do volume geral, que notas sustentadas dominam.
 * 2. Autocorrelação normalizada pela sobreposição de cada lag.
 * 3. Escolha do lag: maior pico local ponderado por um prior log-gaussiano em 120 BPM,
 *    para não cair na metade/dobro do tempo; pico refinado por interpolação parabólica.
 */
object BpmDetector {

    data class Result(val bpm: Float, val confidence: Float)

    private const val MIN_BPM = 60f
    private const val MAX_BPM = 200f
    private const val DEFAULT_BPM = 120f
    private const val PRIOR_BPM = 120.0
    private const val PRIOR_OCTAVES = 1.0

    private const val FFT_SIZE = 1024
    private const val HOP = 256
    private const val LOW_HZ = 2000.0
    private const val HIGH_WEIGHT = 0.3

    private const val SILENCE_RMS = 0.01f
    private const val MIN_CONFIDENCE = 0.1f

    /**
     * @param samples PCM mono, floats no intervalo [-1, 1]
     * @param sampleRate taxa de amostragem (ex.: 44100)
     */
    fun detect(samples: FloatArray, sampleRate: Int): Result {
        if (samples.size < sampleRate * 3) return Result(DEFAULT_BPM, 0f)

        var acc = 0.0
        for (s in samples) acc += s * s
        if (sqrt(acc / samples.size) < SILENCE_RMS) return Result(DEFAULT_BPM, 0f)

        val onset = onsetStrength(samples, sampleRate)
        val n = onset.size
        val fps = sampleRate.toDouble() / HOP

        val minLag = (60.0 * fps / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (60.0 * fps / MIN_BPM).toInt() + 1
        if (maxLag + 2 >= n) return Result(DEFAULT_BPM, 0f)

        // Autocorrelação média por sobreposição (sem isso lags curtos levam vantagem)
        val acf = DoubleArray(maxLag + 2)
        for (lag in acf.indices) {
            var c = 0.0
            for (i in 0 until n - lag) c += onset[i] * onset[i + lag]
            acf[lag] = c / (n - lag)
        }
        if (acf[0] <= 0.0) return Result(DEFAULT_BPM, 0f)

        // Melhor pico local; o prior desempata metade/dobro do tempo
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            if (acf[lag] < acf[lag - 1] || acf[lag] < acf[lag + 1]) continue
            val octaves = log2(60.0 * fps / lag / PRIOR_BPM) / PRIOR_OCTAVES
            val score = acf[lag] * exp(-0.5 * octaves * octaves)
            if (score > bestScore) { bestLag = lag; bestScore = score }
        }
        if (bestLag < 0) return Result(DEFAULT_BPM, 0f)

        val confidence = (acf[bestLag] / acf[0]).toFloat()
        if (confidence < MIN_CONFIDENCE) return Result(DEFAULT_BPM, 0f)

        // Refina o pico entre frames (interpolação parabólica na autocorrelação)
        var lag = bestLag.toDouble()
        if (bestLag in 1 until acf.size - 1) {
            val a = acf[bestLag - 1]; val b = acf[bestLag]; val c = acf[bestLag + 1]
            val den = a - 2 * b + c
            if (den < 0) lag += (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        val bpm = (60.0 * fps / lag).toFloat()
        return Result(bpm.coerceIn(MIN_BPM, MAX_BPM), confidence)
    }

    /** Fluxo espectral (só aumentos de magnitude log), com média zero. */
    private fun onsetStrength(samples: FloatArray, sampleRate: Int): DoubleArray {
        val frames = (samples.size - FFT_SIZE) / HOP
        val bins = FFT_SIZE / 2
        // Agudos (chimbal em colcheias) pesam menos, senão puxam o BPM para o dobro
        val lowBins = (LOW_HZ * FFT_SIZE / sampleRate).toInt()
        val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * Math.PI * it / FFT_SIZE) }
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        var prev = DoubleArray(bins)
        var cur = DoubleArray(bins)
        val onset = DoubleArray(frames)
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FFT_SIZE) { re[i] = samples[off + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            var flux = 0.0
            for (k in 1 until bins) {
                cur[k] = ln(1.0 + 100.0 * sqrt(re[k] * re[k] + im[k] * im[k]))
                if (f > 0 && cur[k] > prev[k]) flux += (cur[k] - prev[k]) * (if (k < lowBins) 1.0 else HIGH_WEIGHT)
            }
            onset[f] = flux
            val t = prev; prev = cur; cur = t
        }
        val mean = onset.average()
        for (i in onset.indices) onset[i] -= mean
        return onset
    }

    /** FFT radix-2 in-place (tamanho potência de 2). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
