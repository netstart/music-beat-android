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
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bpm_player.databinding.ActivityMainBinding
import com.example.bpm_player.databinding.ItemSongBinding
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var detectedBpm: Float = 0f
    private var isRepeating = false
    private var isLoopingAll = false
    private var selectedSongUri: Uri? = null
    private var playingSongUri: Uri? = null
    private var currentArtist: String? = null
    private lateinit var songAdapter: SongAdapter
    private var sortMode: SortMode = SortMode.TITLE
    private var itemTouchHelper: ItemTouchHelper? = null

    enum class SortMode { TITLE, ARTIST, MANUAL }

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
        val artist: String,
        val duration: Long,
        val filePath: String = "" // caminho do arquivo no sistema de arquivos
    )

    // --- SongAdapter for RecyclerView ---
    inner class SongAdapter : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

        private var songs: List<Song> = emptyList()
        private var selectedPosition: Int = RecyclerView.NO_POSITION
        private var playingPosition: Int = RecyclerView.NO_POSITION
        private var showDragHandle: Boolean = false

        inner class SongViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(song: Song, position: Int) {
                binding.songTitle.text = song.title
                binding.songArtist.text = song.artist
                binding.songArtist.visibility = if (song.artist.isNotBlank()) View.VISIBLE else View.GONE
                binding.songPath.text = song.filePath
                binding.songPath.visibility = if (song.filePath.isNotBlank()) View.VISIBLE else View.GONE
                binding.songDuration.text = formatTime(song.duration)
                binding.songDragHandle.visibility = if (showDragHandle) View.VISIBLE else View.GONE

                val isSelected = position == selectedPosition
                val isPlaying = position == playingPosition

                // Click no ícone de play/pause dentro do item da lista
                binding.songIcon.setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val main = this@MainActivity
                    if (isPlaying) {
                        // Está tocando -> pausa e limpa o estado "playing"
                        exoPlayer?.pause()
                        playingSongUri = null
                        songAdapter.setPlayingPosition(RecyclerView.NO_POSITION)
                        main.binding.playFab.setImageResource(R.drawable.ic_play)
                        main.binding.playFab.contentDescription = main.getString(R.string.cd_play)
                    } else {
                        // Não está tocando -> toca esta música
                        selectedSongUri = song.uri
                        songAdapter.setSelectedPosition(position)
                        songAdapter.setPlayingPosition(position)
                        onFilePicked(song.uri)
                    }
                }

                // Highlight selected and playing items
                if (isPlaying) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.songArtist.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.songDuration.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.songIcon.setImageResource(R.drawable.ic_pause)
                    binding.songIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
                } else if (isSelected) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    binding.songArtist.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    binding.songDuration.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    // Selecionado mas pausado: ícone de play
                    binding.songIcon.setImageResource(R.drawable.ic_play)
                    binding.songIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                } else {
                    binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    binding.songTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    binding.songArtist.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
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

        /** Reordena a lista in-place (modo manual) e notifica. */
        fun moveItem(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= songs.size || to >= songs.size) return
            val mutable = songs.toMutableList()
            val item = mutable.removeAt(from)
            mutable.add(to, item)
            songs = mutable
            notifyItemMoved(from, to)
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

        fun setShowDragHandle(show: Boolean) {
            if (showDragHandle == show) return
            showDragHandle = show
            notifyDataSetChanged()
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

        // Setup RecyclerView for song list
        songAdapter = SongAdapter()
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = songAdapter
        binding.songList.setHasFixedSize(true)

        // ItemTouchHelper para reordenação manual
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (sortMode != SortMode.MANUAL) return false
                songAdapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = sortMode == SortMode.MANUAL
        })
        itemTouchHelper?.attachToRecyclerView(binding.songList)

        // Setup gesture detector for RecyclerView items
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleRecyclerViewTap(e, false)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleRecyclerViewTap(e, true)
                return true
            }
        })

        // Listener customizado (não SimpleOnItemTouchListener) para garantir
        // que tanto intercept quanto touch sejam roteados ao GestureDetector.
        // Sem isso, o double-tap falha porque o GestureDetector não recebe
        // o ACTION_UP do segundo toque quando onIntercept retorna true.
        binding.songList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                // Encaminha sempre ao detector; o detector decide o que é tap/double-tap.
                gestureDetector.onTouchEvent(e)
                // Não retornamos true: deixamos o RecyclerView tratar long-press/drag
                // do ItemTouchHelper. Se um double-tap for detectado, o gesto será
                // curto o suficiente para o drag nunca disparar.
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                // Encaminha eventos subsequentes ao detector para que ele possa
                // identificar o segundo ACTION_DOWN do double-tap.
                gestureDetector.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })

        // Botão de ordenação
        binding.sortLabel.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showSortDialog()
        }

        // Permissão + carga inicial
        ensureAudioPermissionAndLoad()

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
        binding.loopAllButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLoopAll()
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
        updateLoopAllVisual()

        // Edge-to-edge: conteúdo ocupa a tela inteira, barras do sistema são transparentes
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // Se a permissão foi concedida enquanto estávamos fora da activity, recarrega.
        if (hasAudioPermission() && songAdapter.songsList().isEmpty()) {
            loadSongs()
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
        if (isRepeating) {
            isLoopingAll = false  // exclusão mútua
        }
        exoPlayer?.repeatMode = if (isRepeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        setRepeatVisualState()
        updateLoopAllVisual()
    }

    private fun toggleLoopAll() {
        isLoopingAll = !isLoopingAll
        if (isLoopingAll) {
            isRepeating = false  // exclusão mútua: desliga repetir música atual
            exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        }
        setRepeatVisualState()
        updateLoopAllVisual()
    }

    private fun updateLoopAllVisual() {
        if (isLoopingAll) {
            binding.loopAllButton.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)
            )
        } else {
            binding.loopAllButton.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun setRepeatVisualState() {
        if (isRepeating) {
            binding.repeatButton.iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent)
            )
        } else {
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

    private var pulseScaleX: ObjectAnimator? = null
    private var pulseScaleY: ObjectAnimator? = null

    private fun pulseBpmValue() {
        if (pulseScaleX == null) {
            pulseScaleX = ObjectAnimator.ofFloat(binding.bpmValue, "scaleX", 1f, 1.05f, 1f).apply {
                duration = 180
            }
            pulseScaleY = ObjectAnimator.ofFloat(binding.bpmValue, "scaleY", 1f, 1.05f, 1f).apply {
                duration = 180
            }
        }
        pulseScaleX?.cancel()
        pulseScaleY?.cancel()
        pulseScaleX?.start()
        pulseScaleY?.start()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun ensureAudioPermissionAndLoad() {
        if (hasAudioPermission()) {
            loadSongs()
        } else {
            requestPermissions(arrayOf(audioPermission()), 1002)
        }
    }

    private fun checkPermissionAndPickFile() {
        if (hasAudioPermission()) {
            filePickerLauncher.launch("audio/*")
        } else {
            requestPermissions(arrayOf(audioPermission()), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            1001 -> if (granted) filePickerLauncher.launch("audio/*")
            1002 -> loadSongs() // carrega mesmo sem permissão: retorna lista vazia
        }
    }

    private fun onFilePicked(uri: Uri) {
        handler.removeCallbacks(positionUpdater)
        playingSongUri = uri
        // Garante que a faixa esteja presente na lista de músicas do dispositivo.
        ensureSongInList(uri)
        // Resolve o nome de exibição da URI de forma robusta:
        // 1) tenta DISPLAY_NAME do content provider (caminho mais confiável)
        // 2) tenta a lista de músicas (MediaStore)
        // 3) extrai do path como último recurso
        val fallbackTitle = resolveTrackTitle(uri)
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

        // ====== PASSO 1: tocar IMEDIATAMENTE ======
        // A música começa a tocar imediatamente, sem esperar BPM.
        startPlayback(uri)

        // ====== PASSO 2: detectar BPM em paralelo (não bloqueia reprodução) ======
        Thread {
            var pcm: AudioDecoder.PcmData? = null
            try {
                pcm = AudioDecoder.decode(applicationContext, uri)
            } catch (e: Exception) {
                // Falha de decoder: não derruba o app, segue normalmente.
            }

            val result = pcm?.let { BpmDetector.detect(it.samples, it.sampleRate) }

            runOnUiThread {
                if (uri != playingSongUri) return@runOnUiThread // outra música já selecionada
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
                    detectedBpm = if (binding.bpmSlider.value > 0f) binding.bpmSlider.value else 100f
                    applyBpm(detectedBpm)
                    binding.trackStatus.text = getString(R.string.toast_no_bpm)
                    Toast.makeText(this, getString(R.string.toast_no_bpm), Toast.LENGTH_SHORT).show()
                }
            }
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

                val displayTitle = metaTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: resolveTrackTitle(uri)

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
                    binding.trackTitle.text = resolveTrackTitle(uri)
                }
            }
        }.start()
    }

    /**
     * Resolve o título de exibição de uma URI de áudio.
     *
     * Ordem de prioridade:
     *  1. Título encontrado no MediaStore (lista de músicas do dispositivo)
     *  2. Coluna DISPLAY_NAME do content provider (nome real do arquivo)
     *  3. Fallback baseado no último segmento da URI
     */
    private fun resolveTrackTitle(uri: Uri): String {
        // 1. Procura na lista de músicas (mais confiável quando há metadados ID3)
        songAdapter.songsList().firstOrNull { it.uri == uri }?.title?.let { return it }

        // 2. DISPLAY_NAME via content provider
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = c.getString(nameIdx)
                        if (!name.isNullOrBlank()) return stripExtension(name)
                    }
                }
            }
        } catch (_: Exception) {
            // alguns providers não suportam — segue
        }

        // 3. Fallback: extrai o último segmento da URI
        return stripExtension(uri.lastPathSegment ?: uri.toString())
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * Cria o ExoPlayer e inicia a reprodução.
     * Chamado IMEDIATAMENTE ao selecionar uma música — sem esperar BPM.
     * Runs independently from the BPM decode thread (no MediaCodec conflict
     * because each MediaCodec instance is independent; Android manages them).
     */
    private fun startPlayback(uri: Uri) {
        // Aborta se o usuário já selecionou outra música enquanto a detecção rodava.
        if (uri != playingSongUri) return

        // Safety: se algo falhar, não crash — apenas volta ao estado anterior.
        try {

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
                        playingSongUri?.let { binding.trackTitle.text = resolveTrackTitle(it) }
                    }

                    // Sincroniza ícone da linha da lista com play/pause
                    val playingIndex = songAdapter.songsList().indexOfFirst { it.uri == playingSongUri }
                    if (isPlaying) {
                        if (playingIndex >= 0) songAdapter.setPlayingPosition(playingIndex)
                    } else {
                        // Ao pausar, mantém o destaque "selected" (selectedSongUri)
                        // mas limpa o destaque "playing" — assim o ícone volta a "play".
                        songAdapter.setPlayingPosition(RecyclerView.NO_POSITION)
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
                                playingSongUri?.let { binding.trackTitle.text = resolveTrackTitle(it) }
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
                            // tentamos tocar a próxima música da lista para manter a
                            // sequência escolhida pelo usuário. Se não houver próxima,
                            // deixamos a faixa pausada no início, com a UI em estado
                            // "pronto para tocar de novo" — sem fechar nada.
                            if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                                val advanced = playNextInQueue()
                                if (!advanced) {
                                    // Pausa o player para que a música não reinicie do
                                    // começo (playWhenReady=true + seekTo(0) seria
                                    // indistinguível de "repetir" para o usuário).
                                    player.playWhenReady = false
                                    player.pause()
                                    binding.playFab.setImageResource(R.drawable.ic_play)
                                    binding.playFab.contentDescription = getString(R.string.cd_play)
                                    player.seekTo(0L)
                                    binding.trackSlider.value = 0f
                                    binding.trackTimeCurrent.text = "0:00"
                                }
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
        } catch (e: Exception) {
            // ExoPlayer/Builder falhou — não derruba o app, mantém estado consistente.
            binding.playFab.isEnabled = true
            binding.playFab.setImageResource(R.drawable.ic_play)
            binding.playFab.contentDescription = getString(R.string.cd_play)
            binding.trackStatus.text = getString(R.string.toast_no_bpm)
            Toast.makeText(this, getString(R.string.toast_no_bpm), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Garante que a URI esteja presente na lista visível. Se já existir,
     * apenas atualiza o destaque (selecionado/tocando). Caso contrário,
     * lê título/artista/duração em background e adiciona à lista,
     * respeitando o modo de ordenação atual.
     */
    private fun ensureSongInList(uri: Uri) {
        val existingIndex = songAdapter.songsList().indexOfFirst { it.uri == uri }
        if (existingIndex >= 0) {
            songAdapter.setSelectedPosition(existingIndex)
            songAdapter.setPlayingPosition(existingIndex)
            return
        }

        // Lê metadados em background para não travar a UI.
        Thread {
            var title = ""
            var artist = ""
            var duration = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim().orEmpty()
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim().orEmpty()
                    .ifEmpty {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            ?.trim().orEmpty()
                    }
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durStr?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (_: Exception) {
                // ignora: usaremos fallbacks abaixo
            }
            if (title.isEmpty()) title = resolveTrackTitle(uri)
            if (artist == "<unknown>") artist = ""

            runOnUiThread {
                val songs = songAdapter.songsList().toMutableList()
                // Evita corrida caso outra música tenha sido adicionada entre o start e o runOnUiThread.
                if (songs.any { it.uri == uri }) {
                    val idx = songs.indexOfFirst { it.uri == uri }
                    songAdapter.setSelectedPosition(idx)
                    songAdapter.setPlayingPosition(idx)
                    return@runOnUiThread
                }
                songs.add(Song(uri, title, artist, duration))
                val sorted = applySortInPlace(songs)
                songAdapter.setSongs(sorted)
                val newIndex = sorted.indexOfFirst { it.uri == uri }
                if (newIndex >= 0) {
                    songAdapter.setSelectedPosition(newIndex)
                    songAdapter.setPlayingPosition(newIndex)
                }
                updateEmptyState()
            }
        }.start()
    }

    private fun playNextInQueue(): Boolean {
        val current = playingSongUri ?: return false
        val songs = songAdapter.songsList()
        if (songs.isEmpty()) return false
        val idx = songs.indexOfFirst { it.uri == current }
        if (idx < 0) return false

        // Se "Tocar todas em sequência" estiver ligado, volta ao início
        // ao chegar na última; caso contrário, para na última.
        val nextIdx = when {
            idx < songs.size - 1 -> idx + 1
            isLoopingAll -> 0
            else -> return false
        }
        val next = songs[nextIdx]
        selectedSongUri = next.uri
        songAdapter.setSelectedPosition(nextIdx)
        songAdapter.setPlayingPosition(nextIdx)
        onFilePicked(next.uri)
        return true
    }

    private fun loadSongs() {
        // Varre dispositivo, SD card e unidades de armazenamento em background
        Thread {
            val songs = mutableListOf<Song>()
            val seenPaths = mutableSetOf<String>()

            // 1) MediaStore — indexa todas as unidades de armazenamento
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
            try {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: "Sem título"
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "" else ""
                        val cleanArtist = if (artist == "<unknown>") "" else artist
                        val duration = cursor.getLong(durCol)
                        val filePath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        if (filePath.isNotBlank()) seenPaths.add(filePath)
                        songs.add(Song(uri, title, cleanArtist, duration, filePath))
                    }
                }
            } catch (_: Exception) { }

            // 2) Varredura direta em todos os volumes de armazenamento (SD card, USB, etc.)
            val storageDirs = mutableListOf<File>()
            try {
                val ext = android.os.Environment.getExternalStorageDirectory()
                if (ext != null && ext.exists()) storageDirs.add(ext)
            } catch (_: Exception) { }
            // Volumes adicionais via /storage/
            try {
                val root = File("/storage")
                if (root.exists() && root.isDirectory) {
                    root.listFiles()?.filter { it.isDirectory && it.name != "emulated" }?.flatMap { dir ->
                        dir.listFiles()?.filter { f -> f.isDirectory && f.name != ".thumbnails" } ?: emptyList()
                    }?.forEach { sub -> storageDirs.add(sub) }
                }
            } catch (_: Exception) { }

            storageDirs.distinct().forEach { dir ->
                try {
                    dir.walkTopDown()
                        .filter { f -> f.isFile && f.extension.equals("mp3", ignoreCase = true) }
                        .forEach { f ->
                            if (seenPaths.contains(f.absolutePath)) return@forEach
                            val path = f.absolutePath
                            val title = f.nameWithoutExtension
                            val uri = Uri.fromFile(f)
                            seenPaths.add(path)
                            songs.add(Song(uri, title, "", 0L, path))
                        }
                } catch (_: Exception) { }
            }

            runOnUiThread {
                val sorted = applySortInPlace(songs.toMutableList())
                songAdapter.setSongs(sorted)
                updateEmptyState()
                if (songs.isNotEmpty()) {
                    binding.trackStatus.text = "${songs.size} música(s) encontrada(s) — dispositivo + SD card + armazenamento"
                }
            }
        }.start()
    }

    /** Aplica o modo de ordenação atual sobre uma cópia mutável da lista. */
    private fun applySortInPlace(list: MutableList<Song>): List<Song> {
        when (sortMode) {
            SortMode.TITLE -> list.sortBy { it.title.lowercase(Locale.getDefault()) }
            SortMode.ARTIST -> {
                list.sortWith(compareBy(
                    { it.artist.lowercase(Locale.getDefault()) },
                    { it.title.lowercase(Locale.getDefault()) }
                ))
            }
            SortMode.MANUAL -> {
                // Em modo manual o adapter já mantém a ordem; aqui só retornamos como estão.
            }
        }
        return list
    }

    private fun updateEmptyState() {
        val isEmpty = songAdapter.songsList().isEmpty()
        if (isEmpty) {
            binding.emptySongList.text = if (hasAudioPermission()) {
                getString(R.string.empty_song_list)
            } else {
                getString(R.string.empty_song_list_no_permission)
            }
            binding.emptySongList.visibility = View.VISIBLE
            binding.songList.visibility = View.GONE
        } else {
            binding.emptySongList.visibility = View.GONE
            binding.songList.visibility = View.VISIBLE
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.sort_title),
            getString(R.string.sort_artist),
            getString(R.string.sort_manual)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.cd_sort)
            .setItems(labels) { _, which ->
                val newMode = when (which) {
                    0 -> SortMode.TITLE
                    1 -> SortMode.ARTIST
                    else -> SortMode.MANUAL
                }
                applySortMode(newMode)
            }
            .show()
    }

    private fun applySortMode(newMode: SortMode) {
        sortMode = newMode
        binding.sortLabel.text = when (newMode) {
            SortMode.TITLE -> getString(R.string.sort_title)
            SortMode.ARTIST -> getString(R.string.sort_artist)
            SortMode.MANUAL -> getString(R.string.sort_manual)
        }
        songAdapter.setShowDragHandle(newMode == SortMode.MANUAL)
        val current = songAdapter.songsList().toMutableList()
        val sorted = applySortInPlace(current)
        songAdapter.setSongs(sorted)
        // Reaplica playing/selected se a música atual ainda estiver na lista.
        val playingIndex = sorted.indexOfFirst { it.uri == playingSongUri }
        if (playingIndex >= 0) songAdapter.setPlayingPosition(playingIndex)
        val selectedIndex = sorted.indexOfFirst { it.uri == selectedSongUri }
        if (selectedIndex >= 0) songAdapter.setSelectedPosition(selectedIndex)
    }

    private fun handleRecyclerViewTap(e: MotionEvent, isDoubleTap: Boolean) {
        val recyclerView = binding.songList
        // Usa as coordenadas do evento no RecyclerView diretamente
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
