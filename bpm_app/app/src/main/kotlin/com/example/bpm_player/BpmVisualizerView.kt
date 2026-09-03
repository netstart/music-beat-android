package com.example.bpm_player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import kotlin.math.min

/**
 * Visualizador BPM pulsante — desenha gráfico sincronizado com a batida.
 * Mostra: BPM central (grande), nota + oitava, seção musical, waveform.
 */
class BpmVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class VisualizerState(
        val bpm: Float = 0f,
        val note: String = "--",
        val octave: Int = 0,
        val section: String = "",
        val waveform: FloatArray = FloatArray(0),
        val confidence: Float = 0f,
        val isPlaying: Boolean = false,
        val progressMs: Long = 0L  // posição atual na música (ms)
    )

    private var state = VisualizerState()

    // Cores do tema (usar as cores do app)
    private val accentColor = Color.parseColor("#FF0077")
    private val accentLight = Color.parseColor("#FFE6F0")
    private val bgColor = Color.parseColor("#1A1A2E")
    private val textColor = Color.parseColor("#F0F0F0")
    private val textSecondary = Color.parseColor("#A0A0A8")

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentLight
        style = Paint.Style.FILL
        alpha = 180
    }

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaintBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val textPaintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textSecondary
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }

    // Animação de pulso: fase 0..1 sincronizada com BPM
    private var pulsePhase = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600L  // 60 BPM = 1s, 120 BPM = 0.5s — ajustado dinamicamente
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    fun update(state: VisualizerState) {
        this.state = state
        // Ajusta duração do pulso pelo BPM
        if (state.bpm > 0) {
            val newDuration = (60_000 / maxOf(state.bpm, 40f)).toLong()
            if (pulseAnimator.duration != newDuration) {
                pulseAnimator.duration = newDuration
            }
            if (!pulseAnimator.isRunning && state.isPlaying) pulseAnimator.start()
            if (!state.isPlaying && pulseAnimator.isRunning) pulseAnimator.pause()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val minDim = min(w, h)

        // Fundo card arredondado
        val corner = minDim * 0.06f
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, bgPaint)

        // Círculo pulsante central (batida)
        val baseRadius = minDim * 0.28f
        val pulseRadius = baseRadius * (1f + pulsePhase * 0.35f)
        val alpha = ((1f - pulsePhase) * 220 + 30).toInt()
        pulsePaint.alpha = alpha.coerceIn(30, 255)
        canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)

        // Onda / waveform no centro (onda senoidal simulada se sem amostras)
        drawWaveform(canvas, cx, cy, baseRadius * 0.65f)

        // BPM grande central
        textPaintBig.textSize = minDim * 0.22f
        val bpmText = "${state.bpm.toInt()}"
        val bpmY = cy + textPaintBig.textSize * 0.25f
        canvas.drawText(bpmText, cx, bpmY, textPaintBig)

        // Nota + Oitava (abaixo do BPM)
        textPaintSmall.textSize = minDim * 0.10f
        val noteText = if (state.note != "--") "${state.note}${state.octave}" else "--"
        canvas.drawText(noteText, cx, bpmY + minDim * 0.18f, textPaintSmall)

        // Seção musical (acima do BPM)
        if (state.section.isNotBlank()) {
            textPaintSmall.textSize = minDim * 0.08f
            val sectionText = state.section
            canvas.drawText(sectionText, cx, cy - baseRadius * 0.55f, textPaintSmall)
        }

        // Confiança / status (base do card)
        val statusText = when {
            state.isPlaying -> "▶ Tocando • ${formatMs(state.progressMs)}"
            state.bpm > 0 -> "BPM detectado • ${state.confidence.toInt()}% conf"
            else -> "⏸ Aguardando..."
        }
        textPaintSmall.textSize = minDim * 0.06f
        canvas.drawText(statusText, cx, h - minDim * 0.08f, textPaintSmall)
    }

    private fun drawWaveform(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val path = Path()
        val samples = state.waveform
        val count = samples.size.coerceAtMost(120)
        if (count < 2) {
            // Sem samples — desenhar onda senoidal simples conforme BPM
            val freq = maxOf(state.bpm / 60f, 1f)
            for (i in 0..60) {
                val t = i / 60f
                val x = cx + (t - 0.5f) * radius * 2
                val y = cy + kotlin.math.sin(t * 2 * Math.PI * freq) * radius * 0.35f
                if (i == 0) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
            }
            canvas.drawPath(path, waveformPaint)
            return
        }

        val amp = radius * 0.6f
        val step = count / 60f
        for (i in 0..60) {
            val idx = (i * step).toInt().coerceIn(0, count - 1)
            val x = cx + (i / 60f - 0.5f) * radius * 2
            val y = cy + samples[idx] * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, waveformPaint)
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }
}
