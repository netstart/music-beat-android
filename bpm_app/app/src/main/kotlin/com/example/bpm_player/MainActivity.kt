package com.example.bpm_player

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.bpm_player.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var detectedBpm: Float = 0f

    private val handler = Handler(Looper.getMainLooper())

    private val positionUpdater = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.duration > 0) {
                    val fraction = (player.currentPosition.toFloat() / player.duration * 1000f)
                        .coerceIn(0f, 1000f)
                    binding.trackSlider.value = fraction
                    binding.trackTimeCurrent.text = formatTime(player.currentPosition)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onFilePicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bpmSlider.setLabelFormatter { value ->
            String.format(Locale.US, "%.0f BPM", value)
        }
        binding.bpmSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) applyBpm(value)
        }

        binding.bpmMinusButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            adjustBpm(-1f)
        }
        binding.bpmPlusButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            adjustBpm(1f)
        }
        binding.bpmResetButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val reference = if (detectedBpm > 0f) detectedBpm else binding.bpmSlider.value
            binding.bpmSlider.value = reference.coerceIn(40f, 200f)
            applyBpm(reference)
        }

        binding.selectButton.setOnClickListener { checkPermissionAndPickFile() }
        binding.playFab.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }

        binding.trackSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                exoPlayer?.let { player ->
                    if (player.duration > 0) {
                        player.seekTo((value / 1000f * player.duration).toLong())
                    }
                }
            }
        }

        applyBpm(binding.bpmSlider.value)
    }

    private fun adjustBpm(delta: Float) {
        val next = (binding.bpmSlider.value + delta).coerceIn(40f, 200f)
        if (next != binding.bpmSlider.value) {
            binding.bpmSlider.value = next
            applyBpm(next)
        }
    }

    private fun applyBpm(targetBpm: Float) {
        binding.bpmValue.text = String.format(Locale.US, "%.0f", targetBpm)
        pulseBpmValue()

        val referenceBpm = if (detectedBpm > 0f) detectedBpm else targetBpm
        val speed = (targetBpm / referenceBpm).coerceIn(0.25f, 4.0f)

        exoPlayer?.setPlaybackSpeed(speed)

        val description = when {
            speed > 1.01f -> "mais rápido"
            speed < 0.99f -> "mais lento"
            else -> "tempo original"
        }
        binding.tempoFactorValue.text =
            String.format(Locale.US, "%.2f× · %s", speed, description)
    }

    private fun pulseBpmValue() {
        val scaleX = ObjectAnimator.ofFloat(binding.bpmValue, "scaleX", 1f, 1.05f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.bpmValue, "scaleY", 1f, 1.05f, 1f)
        listOf(scaleX, scaleY).forEach { animator ->
            animator.duration = 180
            animator.start()
        }
    }

    private fun checkPermissionAndPickFile() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            filePickerLauncher.launch("audio/*")
        } else {
            requestPermissions(arrayOf(permission), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            filePickerLauncher.launch("audio/*")
        }
    }

    private fun onFilePicked(uri: Uri) {
        handler.removeCallbacks(positionUpdater)
        binding.trackTitle.text = uri.toString().substringAfterLast('/')
        binding.trackStatus.text = getString(R.string.status_analyzing)
        binding.trackSlider.value = 0f
        binding.trackSlider.isEnabled = false
        binding.trackTimeCurrent.text = "0:00"
        binding.trackTimeTotal.text = "--:--"
        binding.playFab.isEnabled = false
        detectedBpm = 0f

        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.play()

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.playFab.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.playFab.contentDescription =
                        getString(if (isPlaying) R.string.cd_pause else R.string.cd_play)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.trackSlider.isEnabled = true
                            binding.trackTimeTotal.text = formatTime(player.duration)
                            binding.playFab.isEnabled = true
                            handler.post(positionUpdater)
                        }
                        Player.STATE_ENDED -> {
                            binding.playFab.setImageResource(R.drawable.ic_play)
                            binding.playFab.contentDescription = getString(R.string.cd_play)
                        }
                        else -> Unit
                    }
                }
            })
        }

        Thread {
            val pcm = AudioDecoder.decode(applicationContext, uri, maxDurationUs = 60_000_000L)
            val result = pcm?.let { BpmDetector.detect(it.samples, it.sampleRate) }

            runOnUiThread {
                if (result != null && result.confidence > 0f) {
                    detectedBpm = result.bpm
                    binding.trackStatus.text =
                        getString(R.string.status_bpm_detected, result.bpm)
                    binding.bpmSlider.value = result.bpm.coerceIn(40f, 200f)
                    applyBpm(result.bpm)
                    Toast.makeText(
                        this,
                        getString(R.string.toast_bpm_detected, result.bpm),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    detectedBpm = binding.bpmSlider.value
                    applyBpm(detectedBpm)
                    binding.trackStatus.text = getString(R.string.toast_no_bpm)
                    Toast.makeText(this, getString(R.string.toast_no_bpm), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionUpdater)
        exoPlayer?.release()
    }
}
