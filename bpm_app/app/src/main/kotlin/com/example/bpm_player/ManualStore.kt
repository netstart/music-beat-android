package com.example.bpm_player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

/**
 * Estruturas manuais no aparelho, no layout do emusicality: `manual/songs.json` (catálogo) +
 * `manual/<id>/breakdown.json`. `manual/index.json` liga o NOME DO ARQUIVO da música ao id; o nome é o mesmo
 * em outro aparelho, então a estrutura volta junto com o Backup do Google (res/xml/data_extraction_rules.xml).
 * Chamar fora da main thread.
 */
object ManualStore {

    fun load(context: Context, uri: Uri): ManualStructure? = runCatching {
        val id = index(context).optString(key(context, uri)).ifEmpty { return null }
        val entry = catalog(context).optJSONObject(id) ?: return null
        ManualStructure.fromJson(entry, JSONObject(File(dir(context), "$id/breakdown.json").readText()))
    }.getOrNull()

    fun save(context: Context, uri: Uri, m: ManualStructure) {
        val k = key(context, uri)
        val idx = index(context)
        val old = idx.optString(k)
        if (old.isNotEmpty() && old != m.id) removeId(context, old)
        File(dir(context), m.id).mkdirs()
        File(dir(context), "${m.id}/breakdown.json").writeText(m.toBreakdownJson().toString(2))
        write(context, "songs.json", catalog(context).put(m.id, m.toSongsEntryJson()))
        write(context, "index.json", idx.put(k, m.id))
    }

    fun delete(context: Context, uri: Uri) {
        val k = key(context, uri)
        val idx = index(context)
        idx.optString(k).takeIf { it.isNotEmpty() }?.let { removeId(context, it) }
        idx.remove(k)
        write(context, "index.json", idx)
    }

    private fun removeId(context: Context, id: String) {
        File(dir(context), id).deleteRecursively()
        write(context, "songs.json", catalog(context).apply { remove(id) })
    }

    /** Nome do arquivo da música (estável entre aparelhos), em minúsculas. */
    fun key(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()
        return name.substringAfterLast('/').lowercase()
    }

    private fun dir(context: Context) = File(context.filesDir, "manual").apply { mkdirs() }
    private fun read(context: Context, name: String) =
        File(dir(context), name).takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()
    private fun write(context: Context, name: String, json: JSONObject) = File(dir(context), name).writeText(json.toString(2))
    private fun catalog(context: Context) = read(context, "songs.json")
    private fun index(context: Context) = read(context, "index.json")
}
