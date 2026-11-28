package com.example.bpm_player

import com.example.bpm_player.StructureAnalyzer.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

class StructureAnalyzerTest {

    private val sr = 11025

    /** Bloco de música: compassos de 8 tempos, dois acordes (4 tempos cada), volume. */
    private data class Part(val measures: Int, val chords: List<List<Int>>, val gain: Double)

    // notas MIDI
    private val am = listOf(57, 60, 64); private val f = listOf(53, 57, 60)
    private val c = listOf(48, 52, 55); private val g = listOf(55, 59, 62)
    private val dm = listOf(50, 53, 57); private val e = listOf(52, 56, 59)

    private val intro = Part(4, listOf(dm, e), 0.35)
    private val verse = Part(8, listOf(am, f), 0.7)
    private val chorus = Part(8, listOf(c, g), 1.0)
    private val outro = Part(4, listOf(dm, e), 0.35)
    private val song = listOf(intro, verse, chorus, verse, chorus, outro)

    private fun midiHz(n: Int) = 440.0 * 2.0.pow((n - 69) / 12.0)

    /** Primeiro "1" em [offset]; bumbo em todo tempo, baixo forte no 1 e no 5, acordes trocando no 1 e no 5. */
    private fun render(parts: List<Part>, bpm: Double, offset: Double, pickup: Int = 0): FloatArray {
        val beat = 60.0 / bpm
        val beats = parts.sumOf { it.measures * 8 }
        val n = ((offset + beats * beat + 1.0) * sr).toInt()
        val out = FloatArray(n)
        fun add(t0: Double, len: Double, fn: (Double) -> Double) {
            val s0 = (t0 * sr).toInt()
            for (i in 0 until (len * sr).toInt()) {
                val idx = s0 + i
                if (idx in 0 until n) out[idx] += fn(i.toDouble() / sr).toFloat()
            }
        }
        for (k in 1..pickup) add(offset - k * beat, 0.12, { x -> 0.2 * sin(2 * PI * (50 + 70 * exp(-x * 30)) * x) * exp(-x * 25) })
        var b = 0
        for (p in parts) for (m in 0 until p.measures) for (k in 0 until 8) {
            val t = offset + b * beat
            add(t, 0.12, { x -> 0.5 * p.gain * sin(2 * PI * (50 + 70 * exp(-x * 30)) * x) * exp(-x * 25) })
            if (k == 0 || k == 4) {
                val chord = p.chords[k / 4]
                add(t, 4 * beat, { x -> p.gain * 0.6 * sin(2 * PI * midiHz(chord[0] - 24) * x) * exp(-x * 2) })
                add(t, 4 * beat, { x -> p.gain * 0.12 * chord.sumOf { sin(2 * PI * midiHz(it) * x) } })
            }
            b++
        }
        return out
    }

    @Test
    fun findsGridDownbeatAndSections() {
        val bpm = 128.0
        val offset = 0.37
        val s = StructureAnalyzer.analyze(render(song, bpm, offset), sr, bpm.toFloat())
        assertNotNull(s); s!!
        println("beatMs=${s.beatMs} first=${s.firstBeatMs} downbeat=${s.downbeat} phrase=${s.phraseBeat} ${s.sections}")

        assertEquals(60000.0 / bpm, s.beatMs, 2.0)
        // o tempo marcado como "1" cai num 1 de verdade (±25 ms)
        val oneMs = s.beatStartMs(s.downbeat) - offset * 1000
        val measureMs = 8 * 60000.0 / bpm
        val err = ((oneMs % measureMs) + measureMs) % measureMs
        assertTrue("1 fora do lugar: erro $err ms", err < 25 || measureMs - err < 25)

        assertEquals(listOf(Kind.INTRO, Kind.VERSE, Kind.CHORUS, Kind.VERSE, Kind.CHORUS, Kind.OUTRO), s.sections.map { it.kind })
        val expected = listOf(4, 12, 20, 28, 36).map { it * 8 }
        val got = s.sections.drop(1).map { s.beatStartMs(it.startBeat) - (offset * 1000).toLong() }
        expected.zip(got).forEach { (beats, ms) -> assertTrue("corte em $ms ms, esperado ${beats * 60000 / bpm}", abs(ms - beats * 60000 / bpm) < 40) }

        // refrão de 8 compassos = 2 frases
        assertEquals(2, s.phraseStarts(s.sections[2]).size)
        assertEquals(1, s.count(s.downbeat + 16))
        assertEquals(5, s.count(s.downbeat + 4))
    }

    @Test
    fun findsTheOneAfterPickupBeats() {
        val bpm = 120.0
        val offset = 2.0 // 3 tempos de anacruse antes do primeiro "1"
        val s = StructureAnalyzer.analyze(render(song, bpm, offset, pickup = 3), sr, bpm.toFloat())!!
        val oneMs = s.beatStartMs(s.downbeat) - offset * 1000
        val measureMs = 8 * 60000.0 / bpm
        val err = ((oneMs % measureMs) + measureMs) % measureMs
        println("pickup: first=${s.firstBeatMs} downbeat=${s.downbeat} ${s.sections.map { it.kind }}")
        assertTrue("1 fora do lugar: erro $err ms", err < 25 || measureMs - err < 25)
    }

    @Test
    fun worksAt44kAndOtherTempo() {
        val bpm = 96.0
        val up = render(song, bpm, 0.8)
        val pcm44 = FloatArray(up.size * 4) { up[it / 4] }
        val s = StructureAnalyzer.analyze(pcm44, sr * 4, bpm.toFloat())
        assertNotNull(s)
        assertEquals(60000.0 / bpm, s!!.beatMs, 3.0)
        assertTrue(s.sections.map { it.kind }.contains(Kind.CHORUS))
    }

    @Test
    fun savedTextRoundTrips() {
        val s = StructureAnalyzer.analyze(render(song, 128.0, 0.37), sr, 128f)!!
        val back = StructureStore.decode(StructureStore.encode(s))!!
        assertEquals(s.sections, back.sections)
        assertEquals(s.beatMs, back.beatMs, 1e-4)
        assertEquals(s.beatStartMs(100), back.beatStartMs(100))
        assertEquals(s.count(37), back.count(37))
        assertNull(StructureStore.decode("v0 1 2 3 4\nCHORUS 0 8"))
        assertNull(StructureStore.decode(""))
    }

    @Test
    fun tooShortOrNoBpmGivesNull() {
        assertNull(StructureAnalyzer.analyze(FloatArray(sr * 5), sr, 120f))
        assertNull(StructureAnalyzer.analyze(render(song, 120.0, 0.0), sr, 0f))
    }
}
