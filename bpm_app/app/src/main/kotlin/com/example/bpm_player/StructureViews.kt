package com.example.bpm_player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.example.bpm_player.StructureAnalyzer.Kind
import com.example.bpm_player.StructureAnalyzer.SongStructure

@ColorRes
fun Kind.colorRes(): Int = when (this) {
    Kind.INTRO -> R.color.section_intro
    Kind.VERSE -> R.color.section_verse
    Kind.PRE_CHORUS -> R.color.section_pre_chorus
    Kind.CHORUS -> R.color.section_chorus
    Kind.BRIDGE -> R.color.section_bridge
    Kind.OUTRO -> R.color.section_outro
}

@StringRes
fun Kind.labelRes(): Int = when (this) {
    Kind.INTRO -> R.string.section_intro
    Kind.VERSE -> R.string.section_verse
    Kind.PRE_CHORUS -> R.string.section_pre_chorus
    Kind.CHORUS -> R.string.section_chorus
    Kind.BRIDGE -> R.string.section_bridge
    Kind.OUTRO -> R.string.section_outro
}

/** Barra da música inteira por seções (DESIGN_SYSTEM 4.13). Toque numa seção chama [onSeek] com o início dela. */
class StructureBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var structure: SongStructure? = null
        set(value) { field = value; invalidate() }
    var durationMs: Long = 0L
        set(value) { field = value; invalidate() }
    var positionMs: Long = 0L
        set(value) { field = value; invalidate() }
    var onSeek: ((Long) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.text_primary) }
    private val rect = RectF()

    private fun xOf(ms: Long) = width * (ms.toFloat() / durationMs).coerceIn(0f, 1f)

    private fun sectionEndMs(s: SongStructure, i: Int) =
        if (i == s.sections.size - 1) durationMs else s.beatStartMs(s.sections[i + 1].startBeat)

    override fun onDraw(canvas: Canvas) {
        val s = structure ?: return
        if (durationMs <= 0) return
        val gap = 2 * dp
        val radius = 4 * dp
        val band = 12 * dp // faixa fina; a View segue com 48dp para o toque
        val top = (height - band) / 2
        val bottom = top + band
        val current = s.sectionIndexAt(s.beatAt(positionMs))
        s.sections.forEachIndexed { i, sec ->
            val x0 = if (i == 0) 0f else xOf(s.beatStartMs(sec.startBeat))
            val x1 = xOf(sectionEndMs(s, i))
            fill.color = ContextCompat.getColor(context, sec.kind.colorRes())
            fill.alpha = if (i == current) 255 else 102 // 40%
            rect.set(x0 + gap / 2, top, x1 - gap / 2, bottom)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        val x = xOf(positionMs)
        canvas.drawRect(x - dp, top - 6 * dp, x + dp, bottom + 6 * dp, head)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = structure ?: return false
        if (durationMs <= 0) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val ms = (event.x / width * durationMs).toLong()
            val sec = s.sections[s.sectionIndexAt(s.beatAt(ms))]
            onSeek?.invoke(if (sec === s.sections.first()) 0L else s.beatStartMs(sec.startBeat))
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Contagem de 1 a 8: o tempo atual em accent (DESIGN_SYSTEM 4.13). [count] = 0 apaga todos. */
class CountView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var count: Int = 0
        set(value) {
            if (field == value) return
            field = value
            contentDescription = if (value > 0) context.getString(R.string.cd_count, value) else null
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.outline); style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val onText = ContextCompat.getColor(context, R.color.on_accent)
    private val offText = ContextCompat.getColor(context, R.color.text_tertiary)

    override fun onDraw(canvas: Canvas) {
        val slot = width / 8f
        val r = minOf(slot, height.toFloat()) / 2 - 3 * dp
        val cy = height / 2f
        text.textSize = r
        for (i in 1..8) {
            val cx = slot * (i - 0.5f)
            val active = i == count
            canvas.drawCircle(cx, cy, r, if (active) on else off)
            text.color = if (active) onText else offText
            canvas.drawText(i.toString(), cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
