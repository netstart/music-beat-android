package com.example.bpm_player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Metronomo visual: uma barra que pulsa no tempo 1 (downbeat).
 * A cada batida, a barra cresce e decai. No tempo 1, o pulso é vermelho
 * e maior; nos outros tempos, o pulso é menor/cinza.
 */
class BeatPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isPlaying: Boolean = false
    private var pulseProgress: Float = 0f
    private var currentBeatInCycle: Int = 1 // 1 = downbeat (forte), 2-4 = fracos

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#12121E")
        style = Paint.Style.FILL
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    fun reset() {
        pulseProgress = 0f
        pulseAnimator?.cancel()
        currentBeatInCycle = 1
        invalidate()
    }

    fun update(isPlaying: Boolean, currentBeat: Int) {
        this.isPlaying = isPlaying
        // currentBeat é o número da batida no ciclo (1,2,3,4)
        this.currentBeatInCycle = currentBeat
        // Inicia uma nova animação do pulso a cada chamada se a música está tocando
        if (isPlaying) {
            startPulseAnimation(currentBeatInCycle == 1)
        } else {
            pulseProgress = 0f
            pulseAnimator?.cancel()
        }
        invalidate()
    }

    private fun startPulseAnimation(isDownbeat: Boolean) {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (if (isDownbeat) 180 else 120).toLong()
            addUpdateListener {
                pulseProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun onBeat() {
        // Quando o downbeat acontece, forçamos um pulso grande
        currentBeatInCycle = 1
        startPulseAnimation(true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Fundo
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Barra central
        val barW = w * 0.35f
        val barX = (w - barW) / 2f
        val baseY = h * 0.75f

        // Cor: vermelho intenso no tempo 1, branco nos outros
        val barColor = if (currentBeatInCycle == 1) Color.parseColor("#FF5555") else Color.parseColor("#A0A0A8")
        barPaint.color = barColor

        // Tamanho do pulso: maior no tempo 1
        val maxPulseH = if (currentBeatInCycle == 1) h * 0.55f else h * 0.25f
        val pulseH = maxPulseH * pulseProgress
        val barTop = baseY - pulseH

        // Barra com bordas arredondadas
        val cornerRadius = barW / 4f
        canvas.drawRoundRect(barX, barTop, barX + barW, baseY, cornerRadius, cornerRadius, barPaint)

        // Indicador de ciclo no topo
        val label = "1 · 2 · 3 · 4"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            textSize = spToPx(12)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, w / 2f, h * 0.12f, textPaint)

        // Destaque do tempo atual
        val positions = floatArrayOf(w * 0.20f, w * 0.35f, w * 0.50f, w * 0.65f)
        val dots = listOf(1, 2, 3, 4)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            strokeCap = Paint.Cap.ROUND
        }
        for (i in dots.indices) {
            val cx = positions[i]
            val cyDot = h * 0.22f
            val isCurrent = (i + 1) == currentBeatInCycle
            dotPaint.color = if (isCurrent) barColor else Color.parseColor("#555566")
            val r = if (isCurrent) 6f else 4f
            canvas.drawCircle(cx, cyDot, r, dotPaint)
        }
    }

    private fun spToPx(sp: Int): Float = sp * resources.displayMetrics.scaledDensity
}
