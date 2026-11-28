package com.example.bpm_player

import android.content.Context
import android.net.Uri
import com.example.bpm_player.StructureAnalyzer.Kind
import com.example.bpm_player.StructureAnalyzer.Section
import com.example.bpm_player.StructureAnalyzer.Shift
import com.example.bpm_player.StructureAnalyzer.SongStructure
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Análise de estrutura salva no aparelho (filesDir/structure), uma por música, para não analisar de novo.
 * Texto simples: "v3 beatMs firstBeatMs downbeat phraseBeat", uma linha "KIND início fim" por seção e
 * uma "SHIFT tempo deslocamento" por mudança do 1 (ponte ou ajuste manual). v1 e v2 (seções grosseiras,
 * sem pontes) são ignoradas: a música volta para "Analisar". Chamar fora da main thread.
 */
object StructureStore {

    fun load(context: Context, uri: Uri): SongStructure? =
        file(context, uri).takeIf { it.exists() }?.let { runCatching { decode(it.readText()) }.getOrNull() }

    fun save(context: Context, uri: Uri, s: SongStructure) = file(context, uri).writeText(encode(s))

    fun delete(context: Context, uri: Uri) = file(context, uri).delete()

    private fun file(context: Context, uri: Uri): File {
        val dir = File(context.filesDir, "structure").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.txt")
    }

    fun encode(s: SongStructure): String = buildString {
        append(String.format(Locale.US, "v3 %.6f %.3f %d %d\n", s.beatMs, s.firstBeatMs, s.downbeat, s.phraseBeat))
        s.sections.forEach { append("${it.kind.name} ${it.startBeat} ${it.endBeat}\n") }
        s.shifts.forEach { append("SHIFT ${it.beat} ${it.shift}\n") }
    }

    /** @return null se o texto não for de uma versão conhecida ou estiver incompleto. */
    fun decode(text: String): SongStructure? {
        val lines = text.lines().filter { it.isNotBlank() }
        val head = lines.firstOrNull()?.split(" ") ?: return null
        if (head.size != 5 || head[0] != "v3") return null
        val sections = mutableListOf<Section>()
        val shifts = mutableListOf<Shift>()
        for (line in lines.drop(1)) {
            val (kind, a, b) = line.split(" ")
            if (kind == "SHIFT") shifts += Shift(a.toInt(), b.toInt())
            else sections += Section(Kind.valueOf(kind), a.toInt(), b.toInt())
        }
        if (sections.isEmpty()) return null
        return SongStructure(head[1].toDouble(), head[2].toDouble(), head[3].toInt(), head[4].toInt(), sections, shifts.sortedBy { it.beat })
    }
}
