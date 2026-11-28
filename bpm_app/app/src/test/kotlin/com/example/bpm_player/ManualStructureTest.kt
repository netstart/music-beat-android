package com.example.bpm_player

import com.example.bpm_player.StructureAnalyzer.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualStructureTest {

    /** Estrutura de exemplo no formato do emusicality, com todos os casos que o site aceita. */
    private val breakdown = """
        {
          "trim": 1.5,
          "lag": 0.25,
          "beatsPerMeasure": 8,
          "sections": [
            { "title": "Intro", "phrases": [[{ "beats": 4, "offset": 4 }, 8, 8, 8]] },
            { "title": "Verse", "structure": "Derecho", "phrases": [4] },
            { "title": "Pre Chorus", "structure": "Derecho", "phrases": [[8, 8, 8, 8, 4]] },
            { "title": "Chorus", "phrases": [["Majao", 8, "Derecho", 8], 4] },
            { "title": "Outro", "phrases": [[8, 1]] }
          ]
        }
    """.trimIndent()

    private val song = JSONObject("""{"title":"Exemplo","artist":"Teste","genre":"Bachata","bpm":120,"dateAdded":"2026-11-28T00:00:00Z"}""")

    private fun manual() = ManualStructure.fromJson(song, JSONObject(breakdown))

    @Test
    fun expandsLikeTheSite() {
        val s = manual().toSongStructure()
        // Intro: anacruse de 4 (conta 5..8) + 3 compassos = 28; Verse 32; Pre 36; Chorus 32+32; Outro 9
        assertEquals(listOf(0, 28, 60, 96, 160), s.sections.map { it.startBeat })
        assertEquals(169, s.beatCount)
        assertEquals(500.0, s.beatMs, 1e-9)
        assertEquals(1750.0, s.firstBeatMs, 1e-9) // trim + lag
        assertEquals(5, s.count(0))   // anacruse conta 5
        assertEquals(1, s.count(4))   // depois dela, o 1
        assertEquals(1, s.count(28))  // Verse
        assertEquals(1, s.count(92))  // meio compasso da ponte começa no 1…
        assertEquals(4, s.count(95))
        assertEquals(1, s.count(96))  // …e o Chorus volta a começar no 1
        assertEquals(1, s.count(168)) // batida final
        assertEquals(listOf(0, 28, 60, 96, 128, 160), s.allPhraseStarts())
        assertEquals("Derecho", s.labelAt(40))
        assertEquals("Majao", s.labelAt(100))
        assertEquals("Derecho", s.labelAt(116))
        assertEquals("Pre Chorus", s.sections[2].title)
        assertTrue(s.manual)
    }

    @Test
    fun jsonRoundTripKeepsTheSiteFormat() {
        val m = manual()
        val again = ManualStructure.fromJson(m.toSongsEntryJson(), m.toBreakdownJson())
        assertEquals(m, again)
        // (a ordem das chaves igual à do site vale no Android, cujo org.json mantém a inserção; o da JVM não)
        assertEquals(4, m.toBreakdownJson().getJSONArray("tracks").length())
    }

    @Test
    fun detectedStructureBecomesFormThatExpandsToTheSameCount() {
        val base = manual().toSongStructure()
        val form = ManualStructure.fromDetected(base, manual().song) { it.name }
        val back = form.toSongStructure()
        assertEquals(base.beatCount, back.beatCount)
        for (b in 0 until base.beatCount) assertEquals("tempo $b", base.count(b), back.count(b))
        assertEquals(base.sections.map { it.startBeat }, back.sections.map { it.startBeat })
    }

    @Test
    fun badPhrasesNameTheSection() {
        val m = manual().let { it.copy(sections = it.sections.toMutableList().also { l -> l[2] = l[2].copy(phrases = "[8, 8,") }) }
        val err = runCatching { m.toSongStructure() }.exceptionOrNull()
        assertEquals("2", err?.message)
    }

    @Test
    fun titlesMapToKindsForColor() {
        assertEquals(Kind.PRE_CHORUS, ManualStructure.kindFor("Pré-refrão"))
        assertEquals(Kind.CHORUS, ManualStructure.kindFor("Chorus/Outro"))
        assertEquals(Kind.OUTRO, ManualStructure.kindFor("Ending"))
        assertEquals(Kind.BRIDGE, ManualStructure.kindFor("Mambo"))
        assertEquals("exemploteste", manual().id)
        assertNull(manual().toSongStructure().labelAt(-1))
    }
}
