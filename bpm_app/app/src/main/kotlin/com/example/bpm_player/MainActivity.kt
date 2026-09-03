package com.example.bpm_player

import android.Manifest
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.example.bpm_player.databinding.ActivityMainBinding
import com.example.bpm_player.databinding.ItemSongBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var detectedBpm: Float = 0f
    private var isRepeating = false
    private var selectedSongUri: Uri? = null
    private var playingSongUri: Uri? = null
    private var currentArtist: String? = null
    private lateinit var songAdapter: SongAdapter

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

    // --- Song data class ---
    data class Song(
        val uri: Uri,
        val title: String,
        val duration: Long
    )

    // --- SongAdapter for RecyclerView ---
    inner class SongAdapter : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

        private var songs: List<Song> = emptyList()
        private var selectedPosition: Int = RecyclerView.NO_POSITION
        private var playingPosition: Int = RecyclerView.NO_POSITION

        inner class SongViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(song: Song, position: Int) {
                binding.songTitle.text = song.title
                binding.songDuration.text = formatTime(song.duration)

                val isSelected = position == selectedPosition
                val isPlaying = position == playingPosition

                // Highlight selected and playing items
                if (isPlaying) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.songDuration.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.songIcon.setImageResource(R.drawable.ic_pause)
                    binding.songIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
                } else if (isSelected) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    binding.songDuration.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    binding.songIcon.setImageResource(R.drawable.ic_music_note)
                    binding.songIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                } else {
                    binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    binding.songDuration.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                    binding.songIcon.setImageResource(R.drawable.ic_music_note)
                    binding.songIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
            val binding = ItemSongBinding.inflate(layoutInflater, parent, false)
            return SongViewHolder(binding)
        }

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(songs[position], position)
        }

        override fun getItemCount(): Int = songs.size

        fun setSongs(newSongs: List<Song>) {
            songs = newSongs
            notifyDataSetChanged()
        }

        fun setSelectedPosition(position: Int) {
            val old = selectedPosition
            selectedPosition = position
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
        }

        fun setPlayingPosition(position: Int) {
            val old = playingPosition
            playingPosition = position
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
        }

        fun getSongAt(position: Int): Song? = songs.getOrNull(position)

        fun songsList(): List<Song> = songs
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onFilePicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup RecyclerView for song list
        songAdapter = SongAdapter()
        binding.songList.adapter = songAdapter
        binding.songList.setHasFixedSize(true)

        // Setup gesture detector for RecyclerView items
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleRecyclerViewTap(e, false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleRecyclerViewTap(e, true)
                return true
            }
        })

        binding.songList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(e)
            }
        })

        // Load songs in background
        loadSongs()

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
            binding.bpmSlider.value = reference.toInt().coerceIn(40, 200).toFloat()
            applyBpm(reference)
        }

        binding.selectButton.setOnClickListener { checkPermissionAndPickFile() }
        binding.playFab.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            } ?: run {
                // No player exists - play the selected song from the list
                selectedSongUri?.let { uri ->
                    onFilePicked(uri)
                }
            }
        }
        binding.repeatButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleRepeat()
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
        setRepeatVisualState()

        // Edge-to-edge: conteúdo ocupa a tela inteira, barras do sistema são transparentes
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun adjustBpm(delta: Float) {
        val next = (binding.bpmSlider.value + delta).coerceIn(40f, 200f)
        if (next != binding.bpmSlider.value) {
            binding.bpmSlider.value = next
            applyBpm(next)
        }
    }

    private fun toggleRepeat() {
        isRepeating = !isRepeating
        exoPlayer?.repeatMode =
            if (isRepeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        setRepeatVisualState()
    }

    private fun setRepeatVisualState() {
        if (isRepeating) {
            binding.repeatButton.text = getString(R.string.label_loop_on_state)
            binding.repeatButton.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.repeatButton.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)
            )
        } else {
            binding.repeatButton.text = getString(R.string.label_loop_off_state)
            binding.repeatButton.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.repeatButton.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
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
        playingSongUri = uri
        // Mostra imediatamente um nome "fallback" enquanto os metadados não chegam.
        val fallbackTitle = songAdapter.songsList()
            .firstOrNull { it.uri == uri }
            ?.title
            ?: uri.toString().substringAfterLast('/').substringBeforeLast('.')
        binding.trackTitle.text = fallbackTitle
        binding.trackStatus.text = getString(R.string.status_analyzing)
        binding.trackSlider.value = 0f
        binding.trackSlider.isEnabled = false
        binding.trackTimeCurrent.text = "0:00"
        binding.trackTimeTotal.text = "--:--"
        binding.playFab.isEnabled = false
        detectedBpm = 0f
        currentArtist = null

        // Libera qualquer player anterior antes de criar o novo.
        exoPlayer?.release()
        exoPlayer = null

        // ====== PASSO 1: decodificar PCM e detectar BPM ANTES de tocar ======
        // Serialização intencional: se ExoPlayer e AudioDecoder usassem o
        // MediaCodec simultaneamente, o sistema lançaria IllegalStateException
        // e o app fecharia. Aqui o áudio só começa a tocar depois que a
        // detecção termina.
        Thread {
            var pcm: AudioDecoder.PcmData? = null
            try {
                pcm = AudioDecoder.decode(applicationContext, uri, maxDurationUs = 60_000_000L)
            } catch (e: Exception) {
                // Falha de decoder: não derruba o app, segue com BPM padrão.
            }

            val result = pcm?.let { BpmDetector.detect(it.samples, it.sampleRate) }

            runOnUiThread {
                if (result != null && result.confidence > 0f) {
                    detectedBpm = result.bpm
                    binding.trackStatus.text =
                        getString(R.string.status_bpm_detected, result.bpm)
                    binding.bpmSlider.value = (result.bpm + 0.5f).toInt().coerceIn(40, 200).toFloat()
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

            // ====== PASSO 2: criar ExoPlayer e tocar AGORA que o MediaCodec está livre ======
            runOnUiThread { startPlayback(uri) }
        }.start()

        // Extrai metadados (título/autor) em paralelo — MediaMetadataRetriever
        // usa um caminho separado e não conflita com o decoder.
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaArtistName = metaArtist?.trim().orEmpty().ifEmpty { metaAlbum?.trim().orEmpty() }

                val displayTitle = metaTitle?.trim()
                    ?: songAdapter.songsList().firstOrNull { it.uri == uri }?.title
                    ?: uri.toString().substringAfterLast('/').substringBeforeLast('.')

                val finalArtist = metaArtistName

                runOnUiThread {
                    binding.trackTitle.text = displayTitle
                    currentArtist = finalArtist
                    if (currentArtist?.isNotEmpty() == true) {
                        binding.trackStatus.text = getString(R.string.artist_format, finalArtist, getString(R.string.status_analyzing))
                    }
                }
                retriever.release()
            } catch (e: Exception) {
                // ignora erro de metadados, usa fallback já definido
                runOnUiThread {
                    binding.trackTitle.text = songAdapter.songsList().firstOrNull { it.uri == uri }?.title ?: uri.toString().substringAfterLast('/').substringBeforeLast('.')
                }
            }
        }.start()
    }

    /**
     * Cria o ExoPlayer e inicia a reprodução.
     *
     * Deve ser chamado SOMENTE depois que a detecção de BPM terminou
     * (ver [onFilePicked]), para evitar que dois MediaCodec disputem
     * o mesmo codec de hardware.
     */
    private fun startPlayback(uri: Uri) {
        // Aborta se o usuário já selecionou outra música enquanto a detecção rodava.
        if (uri != playingSongUri) return

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            player.repeatMode =
                if (isRepeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.playFab.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.playFab.contentDescription =
                        getString(if (isPlaying) R.string.cd_pause else R.string.cd_play)

                    // Garante que o nome da música esteja visível.
                    val currentTitle = binding.trackTitle.text.toString()
                    if (currentTitle.isBlank() || currentTitle == getString(R.string.empty_title)) {
                        val song = songAdapter.getSongAt(songAdapter.songsList().indexOfFirst { it.uri == playingSongUri })
                        if (song != null) {
                            binding.trackTitle.text = song.title
                        }
                    }

                    // Keep the row in the song list in sync with the play/pause state.
                    val playingIndex = songAdapter.songsList().indexOfFirst { it.uri == playingSongUri }
                    if (playingIndex >= 0) {
                        songAdapter.setPlayingPosition(playingIndex)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.trackSlider.isEnabled = true
                            binding.trackTimeTotal.text = formatTime(player.duration)
                            binding.playFab.isEnabled = true
                            // Reforça o nome da faixa se ainda estiver vazio.
                            val currentTitle = binding.trackTitle.text.toString()
                            if (currentTitle.isBlank() || currentTitle == getString(R.string.empty_title)) {
                                val fallback = songAdapter.songsList()
                                    .firstOrNull { it.uri == playingSongUri }?.title
                                    ?: playingSongUri?.lastPathSegment ?: "---"
                                binding.trackTitle.text = fallback
                            }
                            // Aplica a velocidade detectada agora que o player está estável.
                            applyBpm(binding.bpmSlider.value)
                            // FADe-in the FAB when playback is ready (200ms routine state change)
                            binding.playFab.alpha = 0f
                            binding.playFab.animate().alpha(1f).setDuration(200).start()
                            handler.post(positionUpdater)
                        }
                        Player.STATE_ENDED -> {
                            // Com loop ativo o ExoPlayer já reinicia sozinho; sem loop
                            // deixamos a faixa pausada no início, com a UI em estado
                            // "pronto para tocar de novo" — sem fechar nada.
                            binding.playFab.setImageResource(R.drawable.ic_play)
                            binding.playFab.contentDescription = getString(R.string.cd_play)
                            if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                                player.seekTo(0L)
                                binding.trackSlider.value = 0f
                                binding.trackTimeCurrent.text = "0:00"
                            }
                        }
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Erro de reprodução (codec, formato, etc.) não deve fechar o app.
                    // Mantemos o player em estado parado para o usuário escolher outra faixa.
                    binding.playFab.setImageResource(R.drawable.ic_play)
                    binding.playFab.contentDescription = getString(R.string.cd_play)
                    binding.trackStatus.text = getString(R.string.toast_no_bpm)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_no_bpm),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
    }

    private fun loadSongs() {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val duration = cursor.getLong(durationColumn)
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                songs.add(Song(uri, title, duration))
            }
        }

        songAdapter.setSongs(songs)
    }

    private fun handleRecyclerViewTap(e: MotionEvent, isDoubleTap: Boolean) {
        val recyclerView = binding.songList
        val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return

        val song = songAdapter.getSongAt(position) ?: return

        if (isDoubleTap) {
            // Double tap = play the song immediately
            selectedSongUri = song.uri
            songAdapter.setSelectedPosition(position)
            songAdapter.setPlayingPosition(position)
            onFilePicked(song.uri)
        } else {
            // Single tap = select only
            selectedSongUri = song.uri
            songAdapter.setSelectedPosition(position)
        }
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
