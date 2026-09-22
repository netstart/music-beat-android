package com.example.bpm_player.stem

import android.net.Uri

data class StemTrack(
    val name: String,
    val fileName: String,
    val colorRes: Int,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val volume: Float = 1.0f
) {
    companion object {
        val DRUMS = StemTrack("Drums", "drums.wav", 0xFF2196F3.toInt())
        val BASS = StemTrack("Bass", "bass.wav", 0xFF4CAF50.toInt())
        val OTHER = StemTrack("Other", "other.wav", 0xFFFF9800.toInt())
        val VOCALS = StemTrack("Vocals", "vocals.wav", 0xFFE91E63.toInt())

        val ALL = listOf(DRUMS, BASS, OTHER, VOCALS)
    }
}
