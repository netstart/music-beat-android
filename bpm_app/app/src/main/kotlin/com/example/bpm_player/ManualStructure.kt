package com.example.bpm_player

import com.example.bpm_player.StructureAnalyzer.BEATS_PER_MEASURE
import com.example.bpm_player.StructureAnalyzer.Kind
import com.example.bpm_player.StructureAnalyzer.Section
import com.example.bpm_player.StructureAnalyzer.Shift
import com.example.bpm_player.StructureAnalyzer.SongStructure
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Estrutura feita à mão, no MESMO formato do emusicality (docs/EMUSICALITY_FORMATO.md):
 * uma entrada do catálogo `songs.json` ([ManualSong]) + o `breakdown.json` da música ([toBreakdownJson]).
 *
 * Frases, como no site: número N = N compassos de 8; lista = um compasso por item (número = tempos do compasso,
 * texto = compasso de 8 que troca o ritmo, objeto {structure, beats, offset} = compasso sob medida).
 */
data class ManualSong(val title: String, val artist: String, val genre: String, val bpm: Double, val dateAdded: String)

/** [phrases] é o conteúdo da lista de frases como texto editável, sem os colchetes de fora: `4, [8, 8, 8, 8, 4]`. */
data class ManualSection(val title: String, val structure: String, val phrases: String)

data class ManualStructure(
    val song: ManualSong,
    val trim: Double,
    val lag: Double,
    val sections: List<ManualSection>,
) {
    /** Id no catálogo, como no site (`desnudate`): título + artista sem acento nem espaço. */
    val id: String get() = slug("${song.title} ${song.artist}")

    fun toSongsEntryJson(): JSONObject = JSONObject()
        .put("title", song.title).put("artist", song.artist).put("genre", song.genre)
        .put("bpm", song.bpm).put("dateAdded", song.dateAdded)

    /** `breakdown.json` com as chaves na ordem do site. */
    fun toBreakdownJson(): JSONObject = JSONObject()
        .put("trim", trim)
        .put("lag", lag)
        .put("beatsPerMeasure", BEATS_PER_MEASURE)
        .put("tracks", STEM_TRACKS)
        .put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().put("title", s.title).apply { if (s.structure.isNotBlank()) put("structure", s.structure) }
                    .put("phrases", phrasesArray(s.phrases)))
            }
        })

    /**
     * Para a estrutura do app: tempo 0 = o "1" em [trim] + [lag] segundos; compassos ≠ 8 viram [Shift]
     * (a contagem se reajusta como numa ponte); frases e ritmos exatos.
     * @throws IllegalArgumentException com a seção cujas frases não são válidas.
     */
    fun toSongStructure(): SongStructure {
        require(song.bpm > 0) { "BPM" }
        val measures = mutableListOf<Measure>()
        val phraseStarts = mutableListOf<Int>()
        val out = mutableListOf<Section>()
        var beat = 0
        for ((i, sec) in sections.withIndex()) {
            val start = beat
            var label = sec.structure.ifBlank { NO_LABEL }
            val phrases = try { phrasesArray(sec.phrases) } catch (e: Exception) { throw IllegalArgumentException("$i") }
            for (p in 0 until phrases.length()) {
                phraseStarts += beat
                when (val ph = phrases.get(p)) {
                    is Number -> repeat(ph.toInt()) { measures += Measure(beat, BEATS_PER_MEASURE, 0, label); beat += BEATS_PER_MEASURE }
                    is JSONArray -> for (k in 0 until ph.length()) {
                        val m = when (val item = ph.get(k)) {
                            is Number -> Measure(beat, item.toInt(), 0, label)
                            is String -> { label = item; Measure(beat, BEATS_PER_MEASURE, 0, label) }
                            is JSONObject -> {
                                label = item.optString("structure", NO_LABEL)
                                Measure(beat, item.optInt("beats", BEATS_PER_MEASURE), item.optInt("offset", 0), label)
                            }
                            else -> throw IllegalArgumentException("$i")
                        }
                        require(m.beats > 0) { "$i" }
                        measures += m
                        beat += m.beats
                    }
                    else -> throw IllegalArgumentException("$i")
                }
            }
            require(beat > start) { "$i" }
            out += Section(kindFor(sec.title), start, beat, sec.title)
        }
        require(out.isNotEmpty()) { "0" }

        // Cada compasso começa contando em offset+1: deslocamento ≡ início − downbeat − offset (mod 8)
        val downbeat = Math.floorMod(-measures.first().offset, BEATS_PER_MEASURE)
        val shifts = mutableListOf<Shift>()
        var cur = 0
        for (m in measures) {
            var d = Math.floorMod(m.start - downbeat - m.offset - cur, BEATS_PER_MEASURE)
            if (d == 0) continue
            if (d > BEATS_PER_MEASURE / 2) d -= BEATS_PER_MEASURE
            cur += d
            shifts += Shift(m.start, cur)
        }
        val labels = measures.fold(mutableListOf<Pair<Int, String>>()) { acc, m ->
            if (acc.lastOrNull()?.second != m.label) acc += m.start to m.label
            acc
        }

        return SongStructure(
            beatMs = 60000.0 / song.bpm,
            firstBeatMs = (trim + lag) * 1000.0,
            downbeat = downbeat,
            phraseBeat = 0,
            sections = out,
            shifts = shifts,
            phraseStartList = phraseStarts.distinct(),
            labels = labels,
            manual = true,
        )
    }

    private class Measure(val start: Int, val beats: Int, val offset: Int, val label: String)

    companion object {
        const val NO_LABEL = "—"

        /** As 4 trilhas que o app separa (HTDemucs), na ordem do arquivo de elementos, no formato `tracks` do site. */
        private val STEM_TRACKS: JSONArray
            get() = JSONArray()
                .put(JSONObject().put("title", "Drums").put("groups", JSONArray().put("Percussion").put("Rhythm")))
                .put(JSONObject().put("title", "Bass").put("groups", JSONArray().put("Rhythm")))
                .put("Other")
                .put(JSONObject().put("title", "Vocals").put("groups", JSONArray().put("Acapella")))

        fun phrasesArray(text: String): JSONArray = JSONArray("[" + text.trim().removePrefix(",").removeSuffix(",") + "]")

        /** Texto editável de uma lista de frases do JSON: `4, [8, 8, 8, 8, 4]`. */
        fun phrasesText(arr: JSONArray): String = arr.toString().removePrefix("[").removeSuffix("]").replace(",", ", ")

        fun slug(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

        /** Nome livre (do site ou em português) → tipo de seção, só para a cor. */
        fun kindFor(title: String): Kind {
            val t = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.US)
            return when {
                "pre" in t && ("chorus" in t || "refr" in t) -> Kind.PRE_CHORUS
                "intro" in t -> Kind.INTRO
                "chorus" in t || "refr" in t || "montuno" in t || "coro" in t -> Kind.CHORUS
                "outro" in t || "ending" in t || "coda" in t || "final" in t -> Kind.OUTRO
                "verse" in t || "verso" in t -> Kind.VERSE
                else -> Kind.BRIDGE
            }
        }

        fun fromJson(songEntry: JSONObject, breakdown: JSONObject): ManualStructure {
            val secs = breakdown.getJSONArray("sections")
            return ManualStructure(
                song = ManualSong(
                    songEntry.optString("title"), songEntry.optString("artist"), songEntry.optString("genre"),
                    songEntry.optDouble("bpm", 0.0), songEntry.optString("dateAdded"),
                ),
                trim = breakdown.optDouble("trim", 0.0),
                lag = breakdown.optDouble("lag", 0.0),
                sections = (0 until secs.length()).map { i ->
                    val s = secs.getJSONObject(i)
                    val ph = s.get("phrases").let { it as? JSONArray ?: JSONArray().put(it) }
                    ManualSection(s.optString("title"), s.optString("structure", ""), phrasesText(ph))
                },
            )
        }

        /**
         * Ponto de partida para o formulário: a análise automática no formato do site. Cada frase vira um número
         * (só compassos de 8) ou uma lista com os tamanhos; o compasso que não começa no 1 vira {beats, offset}.
         */
        fun fromDetected(s: SongStructure, song: ManualSong, sectionTitle: (Kind) -> String): ManualStructure {
            val sections = s.sections.map { sec ->
                val starts = s.phraseStarts(sec) + sec.endBeat
                val phrases = starts.zipWithNext { a, b ->
                    // compassos da frase: de "1" em "1"
                    val ones = (a until b).filter { it == a || s.count(it) == 1 } + b
                    val items = ones.zipWithNext { m0, m1 ->
                        val off = s.count(m0) - 1
                        if (off == 0) (m1 - m0) as Any
                        else JSONObject().put("beats", m1 - m0).put("offset", off)
                    }
                    if (items.all { it == BEATS_PER_MEASURE }) items.size as Any else JSONArray(items)
                }
                ManualSection(sec.title ?: sectionTitle(sec.kind), "", phrasesText(JSONArray(phrases)))
            }
            val trim = Math.round(s.firstBeatMs) / 1000.0
            return ManualStructure(song.copy(bpm = Math.round(60000.0 / s.beatMs * 1000) / 1000.0), trim, 0.0, sections)
        }
    }
}
