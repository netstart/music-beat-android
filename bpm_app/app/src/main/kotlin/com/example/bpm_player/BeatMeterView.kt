package com.example.bpm_player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max

/**
 * Beat meter com 4 barras verticais (4/4).
 * Barra 0 = downbeat (tempo forte, rosa). Demais = tempo fraco (cinza).
 * Cada batida do BPM ativo acende a próxima barra com a energia atual do PCM.
 */
class BeatMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 4
        private const val BAR_SPACING_RATIO = 0.30f
        private const val MIN_BAR_HEIGHT_FRAC = 0.15f
    }

    private var bpm: Float = 120f
    private var energy: Float = 0f
    private var isPlaying: Boolean = false
    private var activeBar: Int = 0

    // Altura de cada barra em pixels (atualizada pelo decay animator)
    private val barPx = FloatArray(BAR_COUNT)
    private val targetPx = FloatArray(BAR_COUNT)

    private var barW: Float = 0f
    private var maxBarH: Float = 0f
    private var stride: Float = 0f

    private var decayAnimator: ValueAnimator? = null

    private val downbeatColor = Color.parseColor("#FF0077")
    private val beatColor = Color.parseColor("#D0D0D8")
    private val dimColor = Color.parseColor("#3A3A5A")

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(bpm: Float, energy: Float, isPlaying: Boolean) {
        this.bpm = bpm
        this.energy = energy.coerceIn(0f, 1f)
        val wasPlaying = this.isPlaying
        this.isPlaying = isPlaying

        if (!isPlaying) {
            decayAnimator?.cancel()
            for (i in barPx.indices) { barPx[i] = 0f; targetPx[i] = 0f }
            invalidate()
            return
        }

        if (!wasPlaying) {
            // Reseta o ciclo de batidas
            activeBar = 0
        }
        invalidate()
    }

    /**
     * Avança para a próxima batida. Chamado a cada intervalo de BPM pela Activity.
     */
    fun onBeat() {
        if (!isPlaying) return
        activeBar = (activeBar + 1) % BAR_COUNT
        scheduleDecay()
        invalidate()
    }

    private fun scheduleDecay() {
        decayAnimator?.cancel()
        // Mantém downbeat mais tempo visível (tempo forte)
        val frac = if (activeBar == 0) 1f else 0.7f
        val startH = max(energy, MIN_BAR_HEIGHT_FRAC) * frac
        targetPx[activeBar] = startH * maxBarH
        // Zera as outras
        for (i in barPx.indices) if (i != activeBar) targetPx[i] = 0f

        val beatMs = (60_000f / bpm).toLong()
        decayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (beatMs * 0.6f).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in barPx.indices) {
                    barPx[i] = targetPx[i] * (1f - t)
                }
                invalidate()
            }
        }
        decayAnimator?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stride = w.toFloat() / BAR_COUNT.toFloat()
        barW = stride * (1f - BAR_SPACING_RATIO)
        maxBarH = h.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        for (i in 0 until BAR_COUNT) {
            val x = i * stride + (stride - barW) / 2f
            val h = barPx[i]
            val halfH = max(h / 2f, 2f)
            barPaint.color = when {
                !isPlaying -> dimColor
                i == 0 && h > 0.5f -> downbeatColor
                h > 0.5f -> beatColor
                else -> dimColor
            }
            canvas.drawRoundRect(
                x, cy - halfH,
                x + barW, cy + halfH,
                barW / 2, barW / 2,
                barPaint
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        decayAnimator?.cancel()
    }
}
