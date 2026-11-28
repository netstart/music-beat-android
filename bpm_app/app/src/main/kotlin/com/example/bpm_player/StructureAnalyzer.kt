package com.example.bpm_player

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estrutura musical da música inteira, calculada offline (ver docs/PLANO_ESTRUTURA.md).
 *
 * 1. Grade de tempos: refina o BPM detectado e acha a fase que mais soma ataques na música toda.
 * 2. O "1" (contagem de 8): o grave cai no 1 do compasso; a troca de harmonia separa o 1 do 5.
 * 3. Frases de 32 tempos alinhadas onde a música mais muda.
 * 4. Seções: cortes na novidade (harmonia + energia) entre compassos de 8 tempos.
 * 5. Rótulos por repetição + energia (Refrão, Verso, Pré-refrão, Ponte, Intro, Final) — são palpites.
 */
object StructureAnalyzer {

    enum class Kind { INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, OUTRO }

    /** Seção em índices de tempo: [startBeat, endBeat). */
    data class Section(val kind: Kind, val startBeat: Int, val endBeat: Int)

    class SongStructure(
        val beatMs: Double,
        val firstBeatMs: Double,
        /** Índice de um tempo que é "1". */
        val downbeat: Int,
        /** Índice de um tempo que começa frase (32 tempos). */
        val phraseBeat: Int,
        val sections: List<Section>,
    ) {
        val beatCount: Int get() = sections.last().endBeat

        // +0,005 tempo: beatStartMs arredonda para ms, e o início de um tempo não pode cair no anterior
        fun beatAt(ms: Long): Int = floor((ms - firstBeatMs) / beatMs + 0.005).toInt().coerceIn(0, beatCount - 1)
        fun beatStartMs(beat: Int): Long = (firstBeatMs + beat * beatMs).roundToLong().coerceAtLeast(0L)
        fun count(beat: Int): Int = Math.floorMod(beat - downbeat, BEATS_PER_MEASURE) + 1
        fun sectionIndexAt(beat: Int): Int = sections.indexOfLast { beat >= it.startBeat }.coerceAtLeast(0)

        /** Inícios de frase da seção: o próprio início + cada borda da grade de 32 tempos dentro dela. */
        fun phraseStarts(section: Section): List<Int> =
            listOf(section.startBeat) + (section.startBeat + 1 until section.endBeat)
                .filter { Math.floorMod(it - phraseBeat, BEATS_PER_PHRASE) == 0 }

        /** Todos os inícios de frase da música, em ordem. */
        fun allPhraseStarts(): List<Int> = sections.flatMap { phraseStarts(it) }

        /** Frase [início, fim) que contém [beat]. */
        fun phraseAt(beat: Int): IntRange {
            val starts = allPhraseStarts()
            val i = starts.indexOfLast { beat >= it }.coerceAtLeast(0)
            return starts[i] until (starts.getOrNull(i + 1) ?: beatCount)
        }
    }

    const val BEATS_PER_MEASURE = 8
    const val BEATS_PER_PHRASE = 32

    private const val TARGET_SR = 11025
    private const val FFT_SIZE = 1024
    private const val HOP = 256
    private const val LOW_HZ = 200.0
    private const val FLUX_HZ = 2000.0
    private const val MIN_SECTION_MEASURES = 4
    private const val NOVELTY_WINDOW = 4

    /** @return null quando não há batida clara ou a música é curta demais. */
    fun analyze(samples: FloatArray, sampleRate: Int, bpm: Float): SongStructure? {
        if (bpm <= 0f) return null
        val factor = (sampleRate.toDouble() / TARGET_SR).roundToInt().coerceAtLeast(1)
        val sr = sampleRate.toDouble() / factor
        val pcm = FloatArray(samples.size / factor) { i ->
            var s = 0f
            for (j in 0 until factor) s += samples[i * factor + j]
            s / factor
        }
        val f = Features.of(pcm, sr)
        if (f.frames < 64) return null
        val fps = sr / HOP

        // 1. Grade: período e fase que mais somam ataques na música inteira
        var bestPeriod = 60.0 * fps / bpm
        var bestPhase = 0.0
        var bestScore = -1.0
        var cand = bpm - 0.6
        while (cand <= bpm + 0.6) {
            val period = 60.0 * fps / cand
            var phase = 0.0
            while (phase < period) {
                var score = 0.0
                var t = phase
                while (t < f.frames - 1) { score += f.onsetAt(t); t += period }
                score /= ((f.frames - phase) / period)
                if (score > bestScore) { bestScore = score; bestPeriod = period; bestPhase = phase }
                phase += 0.5
            }
            cand += 0.02
        }
        val beats = ((f.frames - 1 - bestPhase) / bestPeriod).toInt()
        if (beats < BEATS_PER_PHRASE * 2) return null
        val beatFrame = { b: Int -> bestPhase + b * bestPeriod }

        // Features por tempo
        val low = DoubleArray(beats)
        val chroma = Array(beats) { DoubleArray(12) }
        val energy = DoubleArray(beats)
        for (b in 0 until beats) {
            val c = beatFrame(b).roundToInt()
            for (fr in (c - 2)..(c + 2)) if (fr in 0 until f.frames) low[b] = maxOf(low[b], f.low[fr])
            val end = beatFrame(b + 1).roundToInt().coerceAtMost(f.frames)
            for (fr in c.coerceAtLeast(0) until end) {
                for (k in 0 until 12) chroma[b][k] += f.chroma[fr][k]
                energy[b] += f.rms[fr]
            }
            energy[b] /= (end - c).coerceAtLeast(1)
        }

        // 2. O "1": grave a cada 4 tempos; entre 1 e 5, onde a harmonia mais troca
        val r4 = (0 until 4).maxBy { r -> meanAt(low, r, 4) }
        val change4 = DoubleArray(beats) { b -> novelty(chroma, energy, b, 4) }
        val downbeat = listOf(r4, r4 + 4).maxBy { r -> meanAt(change4, r, BEATS_PER_MEASURE) }

        // 3. Frases: das 4 opções de alinhamento, a que concentra mais mudança nas bordas
        val change16 = DoubleArray(beats) { b -> novelty(chroma, energy, b, 16) }
        val phraseBeat = (0 until 4).map { downbeat + it * BEATS_PER_MEASURE }
            .maxBy { r -> meanAt(change16, r, BEATS_PER_PHRASE) }

        // 4. Seções: novidade entre compassos, cortes nos picos (mínimo 1 frase entre cortes)
        val measures = (beats - downbeat) / BEATS_PER_MEASURE
        if (measures < MIN_SECTION_MEASURES * 2) return null
        val mStart = { m: Int -> downbeat + m * BEATS_PER_MEASURE }
        val mChroma = Array(measures) { m -> sumRange(chroma, mStart(m), mStart(m + 1)) }
        val mEnergy = DoubleArray(measures) { m -> (mStart(m) until mStart(m + 1)).sumOf { energy[it] } / BEATS_PER_MEASURE }
        val nov = DoubleArray(measures) { m ->
            if (m == 0) 0.0 else {
                val w = minOf(NOVELTY_WINDOW, m, measures - m)
                val score = distance(sumRange(mChroma, m - w, m), (m - w until m).sumOf { mEnergy[it] } / w,
                    sumRange(mChroma, m, m + w), (m until m + w).sumOf { mEnergy[it] } / w)
                if (Math.floorMod(mStart(m) - phraseBeat, BEATS_PER_PHRASE) == 0) score * 1.15 else score
            }
        }
        val mean = nov.average()
        val std = sqrt(nov.sumOf { (it - mean) * (it - mean) } / nov.size)
        val cuts = mutableListOf<Int>()
        (MIN_SECTION_MEASURES..measures - MIN_SECTION_MEASURES)
            .filter { m -> nov[m] > mean + 0.5 * std && nov[m] >= nov[m - 1] && nov[m] >= nov[(m + 1).coerceAtMost(measures - 1)] }
            .sortedByDescending { nov[it] }
            .forEach { m -> if (cuts.none { abs(it - m) < MIN_SECTION_MEASURES }) cuts += m }
        val bounds = listOf(0) + cuts.sorted() + measures

        // 5. Rótulos
        data class Seg(val m0: Int, val m1: Int, val chroma: DoubleArray, val energy: Double, var group: Int = -1)
        val segs = bounds.zipWithNext { a, b ->
            Seg(a, b, sumRange(mChroma, a, b), (a until b).sumOf { mEnergy[it] } / (b - a))
        }
        val reps = mutableListOf<Seg>()
        for (s in segs) {
            val g = reps.indexOfFirst { cosine(it.chroma, s.chroma) > 0.95 && abs(ln((it.energy + 1e-9) / (s.energy + 1e-9))) < 0.35 }
            s.group = if (g >= 0) g else { reps += s; reps.size - 1 }
        }
        val groupEnergy = DoubleArray(reps.size) { g -> segs.filter { it.group == g }.map { it.energy }.average() }
        val median = segs.map { it.energy }.sorted()[segs.size / 2]
        val loudest = reps.indices.maxBy { groupEnergy[it] }
        // Intro/Final: pontas mais baixas que a média; ficam fora da contagem de repetição
        val edge = { i: Int -> segs.size > 2 && (i == 0 || i == segs.size - 1) && segs[i].energy < 0.8 * median && segs[i].group != loudest }
        val size = IntArray(reps.size) { g -> segs.indices.count { segs[it].group == g && !edge(it) } }
        val repeated = reps.indices.filter { size[it] >= 2 }
        val chorus = (repeated.ifEmpty { reps.indices.toList() }).maxBy { groupEnergy[it] }
        val verse = repeated.filter { it != chorus }.maxByOrNull { size[it] }

        val kinds = segs.mapIndexed { i, s ->
            val nextChorus = segs.getOrNull(i + 1)?.group == chorus
            when {
                edge(i) && i == 0 -> Kind.INTRO
                edge(i) -> Kind.OUTRO
                s.group == chorus -> Kind.CHORUS
                s.group == verse -> Kind.VERSE
                nextChorus -> Kind.PRE_CHORUS
                size[s.group] >= 2 -> Kind.VERSE
                else -> Kind.BRIDGE
            }
        }

        // Em tempos; a primeira seção leva a anacruse e a última vai até o fim da grade. Iguais vizinhas se juntam.
        val sections = mutableListOf<Section>()
        segs.forEachIndexed { i, s ->
            val start = if (i == 0) 0 else mStart(s.m0)
            val end = if (i == segs.size - 1) beats else mStart(s.m1)
            val prev = sections.lastOrNull()
            if (prev != null && prev.kind == kinds[i]) sections[sections.size - 1] = prev.copy(endBeat = end)
            else sections += Section(kinds[i], start, end)
        }

        // O fluxo de um quadro pica quando o ataque está a ~3/4 da janela de Hann: o tempo real vem depois do início do quadro
        val firstBeatMs = (bestPhase * HOP + FFT_SIZE * 3 / 4) / sr * 1000.0
        return SongStructure(bestPeriod / fps * 1000.0, firstBeatMs, downbeat, phraseBeat, sections)
    }

    private fun meanAt(values: DoubleArray, offset: Int, step: Int): Double {
        var s = 0.0; var n = 0
        var i = offset
        while (i < values.size) { s += values[i]; n++; i += step }
        return if (n == 0) 0.0 else s / n
    }

    /** Diferença entre os [w] tempos antes e os [w] depois de [b]. */
    private fun novelty(chroma: Array<DoubleArray>, energy: DoubleArray, b: Int, w: Int): Double {
        if (b < w || b + w > chroma.size) return 0.0
        return distance(sumRange(chroma, b - w, b), (b - w until b).sumOf { energy[it] } / w,
            sumRange(chroma, b, b + w), (b until b + w).sumOf { energy[it] } / w)
    }

    private fun distance(c1: DoubleArray, e1: Double, c2: DoubleArray, e2: Double): Double =
        (1 - cosine(c1, c2)) + 0.5 * abs(ln((e1 + 1e-9) / (e2 + 1e-9)))

    private fun sumRange(rows: Array<DoubleArray>, from: Int, to: Int): DoubleArray {
        val out = DoubleArray(12)
        for (i in from until to) for (k in 0 until 12) out[k] += rows[i][k]
        return out
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (k in a.indices) { ab += a[k] * b[k]; aa += a[k] * a[k]; bb += b[k] * b[k] }
        return if (aa == 0.0 || bb == 0.0) 1.0 else ab / sqrt(aa * bb)
    }

    /** Por quadro (FFT 1024, hop 256): ataques, ataques do grave, chroma de 12 notas e volume. */
    private class Features(val onset: DoubleArray, val low: DoubleArray, val chroma: Array<DoubleArray>, val rms: DoubleArray) {
        val frames get() = onset.size

        fun onsetAt(t: Double): Double {
            val i = t.toInt()
            val frac = t - i
            return onset[i] * (1 - frac) + onset[i + 1] * frac
        }

        companion object {
            fun of(pcm: FloatArray, sr: Double): Features {
                val frames = ((pcm.size - FFT_SIZE) / HOP).coerceAtLeast(0)
                val bins = FFT_SIZE / 2
                val binHz = sr / FFT_SIZE
                val lowBins = (LOW_HZ / binHz).toInt()
                val fluxBins = (FLUX_HZ / binHz).toInt()
                val pitchClass = IntArray(bins) { k ->
                    val hz = k * binHz
                    if (hz < 55 || hz > 2000) -1 else Math.floorMod((12 * log2(hz / 440.0)).roundToInt() + 9, 12)
                }
                val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * Math.PI * it / FFT_SIZE) }
                val re = DoubleArray(FFT_SIZE)
                val im = DoubleArray(FFT_SIZE)
                var prev = DoubleArray(bins)
                var cur = DoubleArray(bins)
                val onset = DoubleArray(frames)
                val low = DoubleArray(frames)
                val rms = DoubleArray(frames)
                val chroma = Array(frames) { DoubleArray(12) }
                for (fr in 0 until frames) {
                    val off = fr * HOP
                    var e = 0.0
                    for (i in 0 until FFT_SIZE) {
                        val x = pcm[off + i].toDouble()
                        e += x * x
                        re[i] = x * window[i]; im[i] = 0.0
                    }
                    rms[fr] = sqrt(e / FFT_SIZE)
                    fft(re, im)
                    var flux = 0.0
                    var lowFlux = 0.0
                    for (k in 1 until bins) {
                        val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                        cur[k] = ln(1.0 + 100.0 * mag)
                        if (pitchClass[k] >= 0) chroma[fr][pitchClass[k]] += mag
                        if (fr > 0 && cur[k] > prev[k]) {
                            val d = cur[k] - prev[k]
                            flux += if (k < fluxBins) d else 0.3 * d
                            if (k <= lowBins) lowFlux += d
                        }
                    }
                    onset[fr] = flux
                    low[fr] = lowFlux
                    val t = prev; prev = cur; cur = t
                }
                return Features(onset, low, chroma, rms)
            }
        }
    }

    /** FFT radix-2 in-place (mesma do BpmDetector). */
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
