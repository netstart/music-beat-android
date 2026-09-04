package com.example.bpm_player

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Detecção de pitch (frequência fundamental) via autocorrelação normalizada.
 * Converte frequência Hz → nota musical + número da oitava.
 *
 * A autocorrelação é robusta para áudio musical polifônico (voz + instrumentos)
 * porque encontra o período fundamental, não só o pico espectral.
 */
object PitchDetector {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private const val A4_FREQUENCY = 440.0
    // Faixa musical útil: nota mais grave (C0 = 16.35Hz) até C8 (4186Hz)
    private const val MIN_FREQUENCY = 60.0    // C2 (~65Hz) - ignora ruído grave
    private const val MAX_FREQUENCY = 2000.0  // C7 (~2093Hz)
    private const val MIN_CONFIDENCE = 0.4f   // confiança mínima para aceitar

    data class PitchResult(
        val frequency: Float,
        val note: String,
        val octave: Int,
        val noteFull: String,
        val confidence: Float
    )

    /**
     * Detecta a frequência fundamental em uma janela de PCM.
     * @param samples PCM mono em float [-1, 1]
     * @param sampleRate taxa de amostragem (Hz)
     * @return PitchResult ou null se sinal fraco/inválido
     */
    fun detect(samples: FloatArray, sampleRate: Int): PitchResult? {
        if (samples.size < 256) return null

        // 1) Verificar energia RMS mínima (descarta silêncio)
        val rms = calculateRms(samples)
        if (rms < 0.01f) return null

        // 2) Detrendizar (remover DC offset)
        val mean = samples.average().toFloat()
        val detrended = FloatArray(samples.size) { samples[it] - mean }

        // 3) Autocorrelação (ACF)
        val minLag = (sampleRate / MAX_FREQUENCY).toInt()
        val maxLag = (sampleRate / MIN_FREQUENCY).toInt()
        val acf = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0f
            var count = 0
            for (i in 0 until (samples.size - lag)) {
                sum += detrended[i] * detrended[i + lag]
                count++
            }
            acf[lag] = if (count > 0) sum / count else 0f
        }

        // 4) Normalizar pela energia em lag=0
        val energy = acf[0].coerceAtLeast(1e-9f)

        // 5) Encontrar primeiro pico significativo
        val peakLag = findFirstPeak(acf, minLag, maxLag)
        if (peakLag < minLag) return null

        val correlationValue = acf[peakLag] / energy
        if (correlationValue < MIN_CONFIDENCE) return null

        // 6) Refinar com interpolação parabólica ao redor do pico
        val refinedLag = parabolicInterpolation(acf, peakLag)
        if (refinedLag < minLag || refinedLag > maxLag) return null

        val frequency = sampleRate / refinedLag

        // 7) Mapear frequência → nota + oitava
        val (note, octave) = frequencyToNote(frequency)

        return PitchResult(
            frequency = frequency,
            note = note,
            octave = octave,
            noteFull = "$note$octave",
            confidence = correlationValue
        )
    }

    private fun calculateRms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * Encontra o primeiro pico de autocorrelação acima de um limiar relativo.
     * O primeiro pico corresponde à frequência fundamental.
     */
    private fun findFirstPeak(acf: FloatArray, minLag: Int, maxLag: Int): Int {
        // Encontra valor máximo local na faixa válida
        var bestLag = -1
        var bestValue = 0f
        for (lag in (minLag + 1) until maxLag) {
            if (acf[lag] > acf[lag - 1] && acf[lag] > acf[lag + 1] && acf[lag] > bestValue) {
                bestLag = lag
                bestValue = acf[lag]
            }
        }
        return bestLag
    }

    /**
     * Interpolação parabólica para estimar lag sub-sample com mais precisão.
     */
    private fun parabolicInterpolation(acf: FloatArray, peakLag: Int): Float {
        if (peakLag <= 0 || peakLag >= acf.size - 1) return peakLag.toFloat()
        val yMinus = acf[peakLag - 1]
        val y = acf[peakLag]
        val yPlus = acf[peakLag + 1]
        val denom = 2f * (2f * y - yMinus - yPlus)
        if (kotlin.math.abs(denom) < 1e-9f) return peakLag.toFloat()
        val shift = (yMinus - yPlus) / denom
        return peakLag + shift
    }

    /**
     * Converte frequência em Hz para (nota, oitava) usando A4=440Hz.
     * Fórmula: semitons_de_A4 = 12 * log2(f / 440)
     */
    fun frequencyToNote(frequency: Float): Pair<String, Int> {
        if (frequency <= 0f) return "?" to 0
        val semitonesFromA4 = 12.0 * ln(frequency / A4_FREQUENCY) / ln(2.0)
        // A4 está no índice 9 (A), oitava 4
        val totalSemitones = (semitonesFromA4 + 9 + 12 * 10).toInt()
        val noteIndex = ((totalSemitones % 12) + 12) % 12
        val octave = (semitonesFromA4 + 9).toInt() / 12 + 4
        return NOTE_NAMES[noteIndex] to octave
    }
}
