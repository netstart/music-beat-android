package com.example.bpm_player.stem

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class StemPlayerSync(private val context: Context) {

    data class StemState(
        var track: StemTrack,
        val player: ExoPlayer,
        var volumeBeforeSolo: Float = 1.0f
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private var masterPlayer: ExoPlayer? = null
    private val stemStates = mutableListOf<StemState>()
    private var syncJob: Job? = null
    private var isPlaying = false
    private var originalUri: Uri? = null

    private val stemPaths = mutableListOf<String>()

    private val syncRunnable = object : Runnable {
        override fun run() {
            syncStemPositions()
            mainHandler.post(this)
        }
    }

    fun prepareStems(stemFiles: List<File>, originalUri: Uri) {
        release()
        this.originalUri = originalUri

        masterPlayer = ExoPlayer.Builder(context).build().also { player ->
            player.setMediaItem(MediaItem.fromUri(originalUri))
            player.prepare()
            player.playWhenReady = false
            player.volume = 1.0f
        }

        stemStates.clear()
        stemPaths.clear()

        stemFiles.forEachIndexed { index, file ->
            val track = StemTrack.ALL.getOrNull(index) ?: StemTrack.OTHER
            val player = ExoPlayer.Builder(context).build().also { exo ->
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exo.prepare()
                exo.playWhenReady = false
                exo.volume = track.volume
            }
            stemStates.add(StemState(track, player))
            stemPaths.add(file.absolutePath)
        }

        Log.i(TAG, "Prepared ${stemStates.size} stem players")
    }

    fun play() {
        val master = masterPlayer ?: return
        if (master.playbackState == Player.STATE_IDLE) {
            master.prepare()
        }

        master.playWhenReady = true
        stemStates.forEach { it.player.playWhenReady = true }

        isPlaying = true
        mainHandler.post(syncRunnable)
        Log.i(TAG, "Play started")
    }

    fun pause() {
        masterPlayer?.playWhenReady = false
        stemStates.forEach { it.player.playWhenReady = false }
        isPlaying = false
        mainHandler.removeCallbacks(syncRunnable)
        Log.i(TAG, "Paused")
    }

    fun seekTo(positionMs: Long) {
        masterPlayer?.seekTo(positionMs)
        stemStates.forEach { it.player.seekTo(positionMs) }
    }

    fun setStemVolume(index: Int, volume: Float) {
        if (index in stemStates.indices) {
            val state = stemStates[index]
            if (!state.track.isSolo) {
                state.player.volume = if (state.track.isMuted) 0f else volume
            }
        }
    }

    fun toggleMute(index: Int) {
        if (index in stemStates.indices) {
            val state = stemStates[index]
            state.track = state.track.copy(isMuted = !state.track.isMuted)
            applySoloMute()
            Log.i(TAG, "Stem ${state.track.name} mute=${state.track.isMuted}")
        }
    }

    fun toggleSolo(index: Int) {
        if (index in stemStates.indices) {
            val state = stemStates[index]
            state.track = state.track.copy(isSolo = !state.track.isSolo)

            if (state.track.isSolo) {
                stemStates.forEach { s ->
                    if (s != state) {
                        s.track = s.track.copy(isSolo = false)
                    }
                }
            }

            applySoloMute()
            Log.i(TAG, "Stem ${state.track.name} solo=${state.track.isSolo}")
        }
    }

    private fun applySoloMute() {
        val anySoloed = stemStates.any { it.track.isSolo }

        stemStates.forEach { state ->
            if (anySoloed) {
                if (state.track.isSolo) {
                    state.player.volume = if (state.track.isMuted) 0f else 1.0f
                } else {
                    state.player.volume = 0f
                }
            } else {
                state.player.volume = if (state.track.isMuted) 0f else 1.0f
            }
        }
    }

    private fun syncStemPositions() {
        if (!isPlaying) return

        val master = masterPlayer ?: return
        val masterPos = master.currentPosition
        val masterDuration = master.duration

        if (masterDuration <= 0) return

        stemStates.forEach { state ->
            val stemPlayer = state.player
            val drift = kotlin.math.abs(stemPlayer.currentPosition - masterPos)

            if (drift > 50) {
                Log.d(TAG, "Syncing stem ${state.track.name}: drift=${drift}ms")
                stemPlayer.seekTo(masterPos)
            }
        }
    }

    fun currentPosition(): Long = masterPlayer?.currentPosition ?: 0L
    fun duration(): Long = masterPlayer?.duration ?: 0L

    fun isPlaying(): Boolean = isPlaying

    fun getStemStates(): List<StemState> = stemStates.toList()

    fun getOriginalUri(): Uri? = originalUri

    fun release() {
        mainHandler.removeCallbacks(syncRunnable)
        syncJob?.cancel()

        stemStates.forEach { it.player.release() }
        stemStates.clear()

        masterPlayer?.release()
        masterPlayer = null

        stemPaths.clear()
        isPlaying = false
        originalUri = null
        Log.i(TAG, "All players released")
    }
}

private const val TAG = "StemPlayerSync"