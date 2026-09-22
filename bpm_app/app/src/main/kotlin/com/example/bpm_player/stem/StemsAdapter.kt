package com.example.bpm_player.stem

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.bpm_player.databinding.ItemStemBinding
import com.google.android.material.slider.Slider

class StemsAdapter(
    private var stems: List<StemTrack> = StemTrack.ALL.toList(),
    private val onMuteClick: (Int) -> Unit,
    private val onSoloClick: (Int) -> Unit,
    private val onVolumeChange: (Int, Float) -> Unit
) : RecyclerView.Adapter<StemsAdapter.StemViewHolder>() {

    inner class StemViewHolder(val binding: ItemStemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stem: StemTrack, position: Int) {
            binding.stemName.text = stem.name
            binding.stemColorIndicator.setBackgroundColor(stem.colorRes)

            binding.stemVolumeSlider.value = stem.volume
            val volumeListener = Slider.OnChangeListener { _, value, _ ->
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onVolumeChange(adapterPosition, value)
                }
            }
            binding.stemVolumeSlider.addOnChangeListener(volumeListener)

            updateMuteSoloState(stem, position)

            binding.stemMuteButton.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onMuteClick(position)
            }

            binding.stemSoloButton.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onSoloClick(position)
            }
        }

        private fun updateMuteState(stem: StemTrack) {
            val isMuted = stem.isMuted
            binding.stemMuteButton.backgroundTintList = ContextCompat.getColorStateList(
                binding.root.context,
                if (isMuted) com.example.bpm_player.R.color.accent else com.example.bpm_player.R.color.bg_icon_button
            )
            binding.stemMuteButton.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (isMuted) com.example.bpm_player.R.color.on_accent else com.example.bpm_player.R.color.text_tertiary
                )
            )
        }

        private fun updateSoloState(stem: StemTrack) {
            val isSoloed = stem.isSolo
            binding.stemSoloButton.backgroundTintList = ContextCompat.getColorStateList(
                binding.root.context,
                if (isSoloed) com.example.bpm_player.R.color.accent_dim else com.example.bpm_player.R.color.bg_icon_button
            )
            binding.stemSoloButton.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (isSoloed) com.example.bpm_player.R.color.on_accent else com.example.bpm_player.R.color.text_tertiary
                )
            )
        }

        fun updateMuteSoloState(stem: StemTrack, position: Int) {
            updateMuteState(stem)
            updateSoloState(stem)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StemViewHolder {
        val binding = ItemStemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StemViewHolder, position: Int) {
        holder.bind(stems[position], position)
    }

    override fun getItemCount(): Int = stems.size

    fun updateStems(newStems: List<StemTrack>) {
        this.stems = newStems.toList()
        notifyDataSetChanged()
    }

    fun updateStemState(position: Int, stem: StemTrack) {
        if (position in stems.indices) {
            val current = stems[position]
            if (current != stem) {
                val newList = stems.toMutableList()
                newList[position] = stem
                this.stems = newList
                notifyItemChanged(position)
            }
        }
    }
}
