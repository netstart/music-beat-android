package com.example.bpm_player

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.bpm_player.databinding.ItemManualSectionBinding
import com.example.bpm_player.databinding.SheetManualStructureBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Painel deslizante "Estrutura manual" (DESIGN_SYSTEM 4.14). Formulário no formato do emusicality;
 * quem salva/apaga de verdade é [onSave]/[onDelete] (MainActivity, fora da main thread).
 */
class ManualSheet(
    private val activity: AppCompatActivity,
    private val initial: ManualStructure,
    private val exists: Boolean,
    private val detected: StructureAnalyzer.SongStructure?,
    private val positionMs: () -> Long,
    private val onSave: (ManualStructure) -> Unit,
    private val onDelete: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity, R.style.ManualSheetDialog)
    private val b = SheetManualStructureBinding.inflate(activity.layoutInflater)
    private val rows = mutableListOf<ItemManualSectionBinding>()

    fun show() {
        dialog.setContentView(b.root)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        fill(initial)
        b.manualTrimNow.setOnClickListener { v ->
            haptic(v)
            b.manualTrim.setText(String.format(Locale.US, "%.2f", positionMs() / 1000.0))
        }
        b.manualAddSection.setOnClickListener { v -> haptic(v); addRow(ManualSection("", "", "4")).sectionTitle.requestFocus() }
        b.manualFill.setOnClickListener { v ->
            haptic(v)
            val d = detected
            if (d == null) { toast(R.string.manual_no_analysis); return@setOnClickListener }
            val song = read(validate = false)?.song ?: initial.song
            fill(ManualStructure.fromDetected(d, song) { activity.getString(it.labelRes()) })
        }
        b.manualSave.setOnClickListener { v ->
            haptic(v)
            val m = read(validate = true) ?: return@setOnClickListener
            onSave(m)
            dialog.dismiss()
        }
        b.manualDelete.visibility = if (exists) View.VISIBLE else View.GONE
        b.manualDelete.setOnClickListener { v -> haptic(v); onDelete(); dialog.dismiss() }
        b.manualGitHub.setOnClickListener { v ->
            haptic(v)
            val m = read(validate = true) ?: return@setOnClickListener
            activity.startActivity(Intent(Intent.ACTION_VIEW, gitHubIssueUrl(m)))
        }
        dialog.show()
    }

    private fun fill(m: ManualStructure) {
        b.manualTitle.setText(m.song.title)
        b.manualArtist.setText(m.song.artist)
        b.manualGenre.setText(m.song.genre)
        b.manualBpm.setText(if (m.song.bpm > 0) trimNumber(m.song.bpm) else "")
        b.manualTrim.setText(trimNumber(m.trim))
        b.manualLag.setText(trimNumber(m.lag))
        b.manualSections.removeAllViews()
        rows.clear()
        m.sections.forEach { addRow(it) }
    }

    private fun addRow(s: ManualSection): ItemManualSectionBinding {
        val row = ItemManualSectionBinding.inflate(activity.layoutInflater, b.manualSections, true)
        row.sectionTitle.setText(s.title)
        row.sectionStructure.setText(s.structure)
        row.sectionPhrases.setText(s.phrases)
        row.sectionRemove.setOnClickListener { v ->
            haptic(v)
            b.manualSections.removeView(row.root)
            rows.remove(row)
            renumber()
        }
        row.sectionUp.setOnClickListener { v ->
            haptic(v)
            val i = rows.indexOf(row)
            if (i <= 0) return@setOnClickListener
            rows.removeAt(i); rows.add(i - 1, row)
            b.manualSections.removeView(row.root); b.manualSections.addView(row.root, i - 1)
            renumber()
        }
        rows += row
        renumber()
        return row
    }

    private fun renumber() = rows.forEachIndexed { i, r ->
        r.sectionNumber.text = activity.getString(R.string.manual_section_number, i + 1)
        r.sectionUp.isEnabled = i > 0
    }

    /** Lê o formulário; com [validate], marca os erros nos campos e devolve null se houver algum. */
    private fun read(validate: Boolean): ManualStructure? {
        fun txt(v: android.widget.EditText) = v.text?.toString()?.trim().orEmpty()
        fun num(v: android.widget.EditText) = txt(v).replace(',', '.').toDoubleOrNull()
        val title = txt(b.manualTitle)
        val bpm = num(b.manualBpm) ?: 0.0
        val m = ManualStructure(
            song = ManualSong(title, txt(b.manualArtist), txt(b.manualGenre), bpm, initial.song.dateAdded.ifEmpty { nowIso() }),
            trim = num(b.manualTrim) ?: 0.0,
            lag = num(b.manualLag) ?: 0.0,
            sections = rows.map { ManualSection(txt(it.sectionTitle), txt(it.sectionStructure), txt(it.sectionPhrases)) },
        )
        if (!validate) return m
        var ok = true
        b.manualTitleLayout.error = if (title.isEmpty()) { ok = false; activity.getString(R.string.manual_error_required) } else null
        b.manualBpmLayout.error = if (bpm !in 30.0..300.0) { ok = false; activity.getString(R.string.manual_error_bpm) } else null
        rows.forEachIndexed { i, r ->
            r.sectionTitleLayout.error = if (m.sections[i].title.isEmpty()) { ok = false; activity.getString(R.string.manual_error_required) } else null
            r.sectionPhrasesLayout.error = null
        }
        if (rows.isEmpty()) { ok = false; toast(R.string.manual_error_sections) }
        if (ok) {
            val bad = runCatching { m.toSongStructure() }.exceptionOrNull()?.message?.toIntOrNull()
            if (bad != null) { rows.getOrNull(bad)?.sectionPhrasesLayout?.error = activity.getString(R.string.manual_error_phrases); ok = false }
        }
        return if (ok) m else null
    }

    /** Issue pré-preenchida no formulário do repositório (.github/ISSUE_TEMPLATE/estrutura.yml). */
    private fun gitHubIssueUrl(m: ManualStructure): Uri = Uri.parse(GITHUB_NEW_ISSUE).buildUpon()
        .appendQueryParameter("template", "estrutura.yml")
        .appendQueryParameter("title", "Estrutura: ${m.song.title} — ${m.song.artist}")
        .appendQueryParameter("musica", JSONObject().put(m.id, m.toSongsEntryJson()).toString(2))
        .appendQueryParameter("breakdown", m.toBreakdownJson().toString(2))
        .build()

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    private fun toast(res: Int) = android.widget.Toast.makeText(activity, res, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val GITHUB_NEW_ISSUE = "https://github.com/netstart/music-beat-android/issues/new"

        fun trimNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0')

        fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'00:00:00'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
}
