package com.example.bpm_player

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.bpm_player.databinding.ItemBeatPagerBinding

/**
 * Carrossel com 2 gráficos: (1) downbeat meter, (2) metrônomo.
 * Mantém referências de cada view por posição para que ambas atualizem
 * em tempo real independente de qual página está visível.
 */
class BeatPagerAdapter : RecyclerView.Adapter<BeatPagerAdapter.BeatViewHolder>() {

    private val titles = listOf("Downbeat", "Metronomo")
    private val meters = mutableMapOf<Int, BeatMeterView>()
    private val pulses = mutableMapOf<Int, BeatPulseView>()

    fun onUpdate(bpm: Float, rms: Float, isPlaying: Boolean, beatInCycle: Int = 1) {
        meters.values.forEach { it.update(bpm, rms, isPlaying) }
        pulses.values.forEach { it.update(isPlaying, beatInCycle) }
    }

    fun fireBeat() {
        meters.values.forEach { it.onBeat() }
        pulses.values.forEach { it.onBeat() }
    }

    /** Limpa todos os gráficos (chamado ao trocar de música) */
    fun reset() {
        meters.values.forEach { it.reset() }
        pulses.values.forEach { it.reset() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BeatViewHolder {
        val binding = ItemBeatPagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BeatViewHolder(binding)
    }

    override fun getItemCount(): Int = titles.size

    override fun onBindViewHolder(holder: BeatViewHolder, position: Int) {
        holder.bind(position, titles[position])
    }

    override fun onViewRecycled(holder: BeatViewHolder) {
        super.onViewRecycled(holder)
        holder.boundPosition?.let { pos ->
            meters.remove(pos)
            pulses.remove(pos)
        }
    }

    inner class BeatViewHolder(val binding: ItemBeatPagerBinding) : RecyclerView.ViewHolder(binding.root) {
        var boundPosition: Int? = null

        fun bind(position: Int, title: String) {
            boundPosition = position
            binding.pagerTitle.text = title
            // Limpa e adiciona a view correspondente
            binding.pagerContainer.removeAllViews()
            // Limpa referências antigas dessa posição antes de criar a nova view
            meters.remove(position)
            when (position) {
                0 -> {
                    val meter = BeatMeterView(binding.root.context)
                    meter.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    binding.pagerContainer.addView(meter)
                    meters[position] = meter
                }
                1 -> {
                    val pulse = BeatPulseView(binding.root.context)
                    pulse.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    binding.pagerContainer.addView(pulse)
                    pulses[position] = pulse
                }
            }
        }
    }
}
