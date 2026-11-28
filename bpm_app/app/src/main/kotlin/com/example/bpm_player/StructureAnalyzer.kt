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
 * 2. O "1" (contagem de 8), tempo a tempo (Viterbi): o grave cai no 1 e no 5, as seções entram no 1. Pontes com
 *    compasso curto/longo mudam o lugar do 1; o caminho pode "pular" de fase com penalidade, só quando a evidência
 *    se sustenta. Cada pulo vira um [Shift]. O toque do usuário no 1 também ([SongStructure.withOneAt]).
 * 3. Frases de 32 tempos alinhadas onde a música mais muda (seguindo os pulos).
 * 4. Seções: cortes na novidade (harmonia + energia) entre compassos (de 1 em 1).
 * 5. Rótulos por repetição + energia (Refrão, Verso, Pré-refrão, Ponte, Intro, Final) — são palpites.
 */
object StructureAnalyzer {

    enum class Kind { INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, OUTRO }

    /** Seção em índices de tempo: [startBeat, endBeat). */
    data class Section(val kind: Kind, val startBeat: Int, val endBeat: Int)

    /** A partir de [beat], a contagem anda [shift] tempos (ponte com compasso curto/longo, ou ajuste manual). */
    data class Shift(val beat: Int, val shift: Int)

    class SongStructure(
        val beatMs: Double,
        val firstBeatMs: Double,
        /** Índice de um tempo que é "1". */
        val downbeat: Int,
        /** Índice de um tempo que começa frase (32 tempos). */
        val phraseBeat: Int,
        val sections: List<Section>,
        /** Ordenados por tempo. Vazio = o 1 nunca muda de lugar. */
        val shifts: List<Shift> = emptyList(),
    ) {
        val beatCount: Int get() = sections.last().endBeat

        fun shiftAt(beat: Int): Int = shifts.lastOrNull { it.beat <= beat }?.shift ?: 0

        // +0,005 tempo: beatStartMs arredonda para ms, e o início de um tempo não pode cair no anterior
        fun beatAt(ms: Long): Int = floor((ms - firstBeatMs) / beatMs + 0.005).toInt().coerceIn(0, beatCount - 1)
        fun beatStartMs(beat: Int): Long = (firstBeatMs + beat * beatMs).roundToLong().coerceAtLeast(0L)
        fun count(beat: Int): Int = Math.floorMod(beat - downbeat - shiftAt(beat), BEATS_PER_MEASURE) + 1
        fun isPhraseStart(beat: Int): Boolean = Math.floorMod(beat - phraseBeat - shiftAt(beat), BEATS_PER_PHRASE) == 0

        /**
         * "O 1 é aqui": a partir de [beat] a contagem recomeça nele, no mesmo BPM. Os pulos seguintes (pontes)
         * andam junto, então a relação entre eles e a música continua a mesma.
         */
        fun withOneAt(beat: Int): SongStructure {
            var d = Math.floorMod(count(beat) - 1, BEATS_PER_MEASURE)
            if (d > BEATS_PER_MEASURE / 2) d -= BEATS_PER_MEASURE
            if (d == 0) return this
            val kept = shifts.filter { it.beat < beat }
            val moved = shifts.filter { it.beat > beat }.map { it.copy(shift = it.shift + d) }
            return SongStructure(beatMs, firstBeatMs, downbeat, phraseBeat, sections, kept + Shift(beat, shiftAt(beat) + d) + moved)
        }
        fun sectionIndexAt(beat: Int): Int = sections.indexOfLast { beat >= it.startBeat }.coerceAtLeast(0)

        /** Inícios de frase da seção: o próprio início + cada borda da grade de 32 tempos dentro dela. */
        fun phraseStarts(section: Section): List<Int> =
            listOf(section.startBeat) + (section.startBeat + 1 until section.endBeat)
                .filter { isPhraseStart(it) }

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
    private const val SECTION_MEASURES = 6 // ~1 seção a cada 6 compassos de 8 (≈ 48 tempos)

    /** @return null quando não há batida clara ou a música é curta demais. */
    fun analyze(samples: FloatArray, sampleRate: Int, bpm: Float, trace: ((String) -> Unit)? = null): SongStructure? {
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

        // 1. Grade: período e fase que mais somam ataques na música inteira. O BPM vem de 20 s e pode errar ~1%;
        //    em 400+ tempos isso escorrega um tempo inteiro, então a busca cobre ±3% (grossa) e depois refina.
        fun gridScore(period: Double, phase: Double): Double {
            var score = 0.0
            var t = phase
            while (t < f.frames - 1) { score += f.onsetAt(t); t += period }
            return score / ((f.frames - phase) / period)
        }
        var bestPeriod = 60.0 * fps / bpm
        var bestPhase = 0.0
        var bestScore = -1.0
        fun search(bpmFrom: Double, bpmTo: Double, bpmStep: Double, phaseFrom: (Double) -> Double, phaseTo: (Double) -> Double, phaseStep: Double) {
            var cand = bpmFrom
            while (cand <= bpmTo) {
                val period = 60.0 * fps / cand
                var phase = phaseFrom(period)
                while (phase < phaseTo(period)) {
                    if (phase >= 0) {
                        val score = gridScore(period, phase)
                        if (score > bestScore) { bestScore = score; bestPeriod = period; bestPhase = phase }
                    }
                    phase += phaseStep
                }
                cand += bpmStep
            }
        }
        search(bpm * 0.97, bpm * 1.03, 0.05, { 0.0 }, { it }, 1.0)
        val coarseBpm = 60.0 * fps / bestPeriod
        val coarsePhase = bestPhase
        search(coarseBpm - 0.06, coarseBpm + 0.06, 0.005, { coarsePhase - 1.0 }, { coarsePhase + 1.0 }, 0.1)
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

        // 2. O "1", tempo a tempo
        trace?.invoke("grid bpm=%.3f phaseFrames=%.1f beats=$beats".format(60.0 * fps / bestPeriod, bestPhase))
        trace?.invoke("low mod4: " + (0 until 4).joinToString { "%.2f".format(meanAt(low, it, 4)) })
        trace?.invoke("low mod8: " + (0 until 8).joinToString { "%.2f".format(meanAt(low, it, 8)) })
        // As seções entram no 1: salto de volume (4 tempos depois / 4 antes). O grave forte no 5 é comum (arranjo).
        val entry = DoubleArray(beats) { b ->
            if (b < 4 || b + 4 > beats) 0.0 else {
                val j = ln(((b until b + 4).sumOf { energy[it] } + 1e-9) / ((b - 4 until b).sumOf { energy[it] } + 1e-9))
                if (j > 0) j * j else 0.0
            }
        }
        val shifts = mutableListOf<Shift>()
        val change8 = DoubleArray(beats) { b -> novelty(chroma, energy, b, 8) }
        val downbeat = trackDownbeat(low, entry, change8, shifts)
        val shiftAt = { b: Int -> shifts.lastOrNull { it.beat <= b }?.shift ?: 0 }
        trace?.invoke("entry mod8: " + (0 until 8).joinToString { "%.4f".format(meanAt(entry, it, 8)) } + " -> downbeat=$downbeat shifts=$shifts")

        // 3. Frases: das 4 opções de alinhamento, a que concentra mais mudança nas bordas
        val change16 = DoubleArray(beats) { b -> novelty(chroma, energy, b, 16) }
        val ones = (0 until beats).filter { Math.floorMod(it - downbeat - shiftAt(it), BEATS_PER_MEASURE) == 0 }
        val phraseBeat = (0 until 4).map { downbeat + it * BEATS_PER_MEASURE }.maxBy { r ->
            ones.filter { Math.floorMod(it - r - shiftAt(it), BEATS_PER_PHRASE) == 0 }.map { change16[it] }.average()
        }
        val phraseStart = { b: Int -> Math.floorMod(b - phraseBeat - shiftAt(b), BEATS_PER_PHRASE) == 0 }

        // 4. Seções: novidade entre compassos, cortes nos picos (mínimo 1 frase entre cortes)
        // Compassos de "1" em "1" (pontes deixam alguns mais curtos ou mais longos)
        val measures = ones.size - 1
        if (measures < MIN_SECTION_MEASURES * 2) return null
        val mStart = { m: Int -> ones[m] }
        val mChroma = Array(measures) { m -> sumRange(chroma, mStart(m), mStart(m + 1)) }
        val mEnergy = DoubleArray(measures) { m -> (mStart(m) until mStart(m + 1)).sumOf { energy[it] } / (mStart(m + 1) - mStart(m)) }
        val nov = DoubleArray(measures) { m ->
            if (m == 0) 0.0 else {
                val w = minOf(NOVELTY_WINDOW, m, measures - m)
                val score = distance(sumRange(mChroma, m - w, m), (m - w until m).sumOf { mEnergy[it] } / w,
                    sumRange(mChroma, m, m + w), (m until m + w).sumOf { mEnergy[it] } / w)
                if (phraseStart(mStart(m))) score * 1.15 else score
            }
        }
        // Os maiores picos de novidade, mirando ~1 seção a cada SECTION_MEASURES compassos. Limite absoluto não
        // serve: um pico enorme (fim de pausa) sobe a média/desvio e apaga todos os outros cortes.
        val medianNov = nov.sorted()[nov.size / 2]
        val target = (measures / SECTION_MEASURES).coerceAtLeast(2) - 1
        val cuts = mutableListOf<Int>()
        (MIN_SECTION_MEASURES..measures - MIN_SECTION_MEASURES)
            .filter { m -> nov[m] > medianNov && nov[m] >= nov[m - 1] && nov[m] >= nov[(m + 1).coerceAtMost(measures - 1)] }
            .sortedByDescending { nov[it] }
            .forEach { m -> if (cuts.size < target && cuts.none { abs(it - m) < MIN_SECTION_MEASURES }) cuts += m }
        val bounds = listOf(0) + cuts.sorted() + measures
        trace?.invoke("novelty mediana=%.3f alvo=$target cuts(compassos)=$bounds".format(medianNov))

        // 5. Rótulos
        data class Seg(val m0: Int, val m1: Int, val chroma: DoubleArray, val energy: Double, var group: Int = -1)
        val segs = bounds.zipWithNext { a, b ->
            Seg(a, b, sumRange(mChroma, a, b), (a until b).sumOf { mEnergy[it] } / (b - a))
        }
        // Grupos por distância relativa à própria música: numa mesma tonalidade os acordes médios de tudo se parecem,
        // então um limite fixo de cosseno junta a música inteira num grupo só.
        val segDist = { a: Seg, b: Seg -> distance(a.chroma, a.energy, b.chroma, b.energy) }
        val pairs = segs.indices.flatMap { i -> (i + 1 until segs.size).map { j -> segDist(segs[i], segs[j]) } }
        val sameGroup = if (pairs.isEmpty()) 0.0 else pairs.sorted()[pairs.size / 2] * 0.6
        val reps = mutableListOf<Seg>()
        for (s in segs) {
            val g = reps.indexOfFirst { segDist(it, s) < sameGroup }
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

        segs.forEach { trace?.invoke("seg m${it.m0}-${it.m1} energia=%.3f grupo=${it.group}".format(it.energy)) }
        // Se o "refrão" cobriria mais da metade dos trechos, só os mais fortes dele são refrão; os outros, verso.
        val chorusSegs = segs.indices.filter { segs[it].group == chorus && !edge(it) }
        val chorusFloor = if (chorusSegs.size * 2 > segs.size) chorusSegs.map { segs[it].energy }.average() else 0.0
        val kinds = segs.mapIndexed { i, s ->
            val nextChorus = segs.getOrNull(i + 1)?.let { it.group == chorus && it.energy >= chorusFloor } == true
            when {
                edge(i) && i == 0 -> Kind.INTRO
                edge(i) -> Kind.OUTRO
                s.group == chorus && s.energy < chorusFloor -> Kind.VERSE
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
        return SongStructure(bestPeriod / fps * 1000.0, firstBeatMs, downbeat, phraseBeat, sections, shifts)
    }

    private const val JUMP_PENALTY = 8.0 // ~6 compassos de grave fora do lugar, ou 2 entradas de seção, para pular
    private const val ENTRY_WEIGHT = 0.5
    private const val CHANGE_WEIGHT = 0.5
    private const val CAP = 8.0

    /**
     * Posição (0..7) de cada tempo na contagem de 8, por Viterbi. Ganho: grave no 1 e no 5; no 1, entrada de volume e
     * troca de harmonia (8 tempos antes × 8 depois), que é o que separa o 1 do 5 quando o grave não distingue.
     * Andar uma posição por tempo é grátis; qualquer outro passo (compasso curto ou longo numa ponte) custa [JUMP_PENALTY].
     * Preenche [shifts] e devolve o índice de um tempo que é "1" no início.
     */
    private fun trackDownbeat(low: DoubleArray, entry: DoubleArray, change: DoubleArray, shifts: MutableList<Shift>): Int {
        val n = low.size
        val lowMean = low.average().takeIf { it > 0 } ?: 1.0
        val entryMean = entry.average().takeIf { it > 0 } ?: 1.0
        val changeMean = change.average().takeIf { it > 0 } ?: 1.0
        val m = BEATS_PER_MEASURE
        fun gain(b: Int, s: Int): Double = when (s) {
            0 -> low[b] / lowMean + ENTRY_WEIGHT * minOf(entry[b] / entryMean, CAP) + CHANGE_WEIGHT * minOf(change[b] / changeMean, CAP)
            m / 2 -> low[b] / lowMean
            else -> 0.0
        }
        var score = DoubleArray(m) { gain(0, it) }
        val back = Array(n) { IntArray(m) }
        for (b in 1 until n) {
            val best = score.indices.maxBy { score[it] }
            val next = DoubleArray(m)
            for (s in 0 until m) {
                val prev = Math.floorMod(s - 1, m)
                val (from, value) = if (score[prev] >= score[best] - JUMP_PENALTY) prev to score[prev] else best to score[best] - JUMP_PENALTY
                back[b][s] = from
                next[s] = value + gain(b, s)
            }
            score = next
        }
        val pos = IntArray(n)
        pos[n - 1] = score.indices.maxBy { score[it] }
        for (b in n - 1 downTo 1) pos[b - 1] = back[b][pos[b]]

        val downbeat = Math.floorMod(-pos[0], m)
        var cur = 0
        for (b in 1 until n) {
            if (pos[b] == Math.floorMod(pos[b - 1] + 1, m)) continue
            var d = Math.floorMod(b - downbeat - pos[b] - cur, m)
            if (d > m / 2) d -= m
            cur += d
            shifts += Shift(b, cur)
        }
        return downbeat
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
