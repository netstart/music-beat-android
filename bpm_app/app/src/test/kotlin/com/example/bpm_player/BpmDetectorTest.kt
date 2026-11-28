package com.example.bpm_player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BpmDetectorTest {

    private val sr = 44100

    /** Bateria sintética: bumbo nos tempos, caixa no 2 e 4, chimbal em colcheias, pad sustentado. */
    private fun beat(bpm: Double, seconds: Double = 15.0, offset: Double = 0.37, swing: Boolean = false, hat: Double = 0.15): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val rnd = Random(bpm.toInt())
        val beatLen = 60.0 / bpm
        fun hit(t0: Double, len: Double, amp: Double, f: (Double) -> Double) {
            val s0 = (t0 * sr).toInt()
            for (i in 0 until (len * sr).toInt()) {
                val idx = s0 + i
                if (idx !in 0 until n) continue
                val t = i.toDouble() / sr
                out[idx] += (amp * f(t)).toFloat()
            }
        }
        var k = 0
        var t = offset
        while (t < seconds) {
            hit(t, 0.15, 0.8) { x -> sin(2 * PI * (50 + 80 * exp(-x * 30)) * x) * exp(-x * 20) }
            if (k % 2 == 1) hit(t, 0.12, 0.4) { x -> (rnd.nextDouble() * 2 - 1) * exp(-x * 25) }
            val off = if (swing) beatLen * 2 / 3 else beatLen / 2
            // chimbal: ruído em ~10 kHz (agudo, como num chimbal de verdade)
            hit(t, 0.03, hat) { x -> sin(2 * PI * 9000 * x) * exp(-x * 120) }
            hit(t + off, 0.03, hat) { x -> sin(2 * PI * 9000 * x) * exp(-x * 120) }
            k++; t += beatLen
        }
        for (i in 0 until n) out[i] += (0.2 * sin(2 * PI * 220 * i / sr) + 0.15 * sin(2 * PI * 277.2 * i / sr)).toFloat()
        return out
    }

    @Test
    fun detectsKnownTempos() {
        val fails = mutableListOf<String>()
        for (bpm in listOf(85.0, 95.0, 100.0, 110.0, 120.0, 128.0, 140.0, 150.0)) {
            for (swing in listOf(false, true)) {
                val r = BpmDetector.detect(beat(bpm, swing = swing), sr)
                println("bpm=$bpm swing=$swing -> ${r.bpm} conf=${r.confidence}")
                if (abs(r.bpm - bpm) > 0.5) fails += "$bpm${if (swing) "s" else ""}→${r.bpm}"
            }
        }
        assertTrue("erros: $fails", fails.isEmpty())
    }

    // shortcut: nos extremos (70, 160+) pode sair o dobro/metade, como no librosa; aceito
    @Test
    fun extremeTemposLandOnSameOctaveFamily() {
        for (bpm in listOf(70.0, 160.0, 174.0)) {
            val got = BpmDetector.detect(beat(bpm), sr).bpm
            val ok = listOf(bpm, bpm * 2, bpm / 2).any { abs(got - it) < 1.0 }
            assertTrue("$bpm→$got", ok)
        }
    }

    @Test
    fun loudEighthHatsDoNotDoubleTempo() {
        for (bpm in listOf(80.0, 90.0, 96.0)) {
            val r = BpmDetector.detect(beat(bpm, hat = 0.6), sr)
            println("hat bpm=$bpm -> ${r.bpm}")
            assertTrue("$bpm→${r.bpm}", abs(r.bpm - bpm) < 0.5)
        }
    }

    @Test
    fun noiseIsNotDetected() {
        val rnd = Random(1)
        val r = BpmDetector.detect(FloatArray(sr * 15) { (rnd.nextDouble() * 0.6 - 0.3).toFloat() }, sr)
        println("noise -> $r")
        assertTrue("$r", r.confidence == 0f)
    }

    @Test
    fun silenceIsNotDetected() {
        assertTrue(BpmDetector.detect(FloatArray(sr * 10), sr).confidence == 0f)
    }
}
