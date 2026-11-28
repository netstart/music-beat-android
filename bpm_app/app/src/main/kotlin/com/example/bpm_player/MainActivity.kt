package com.example.bpm_player

import android.Manifest
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.media.MediaScannerConnection
import android.os.Environment
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bpm_player.databinding.ActivityMainStemkitBinding
import com.example.bpm_player.databinding.ItemSongBinding
import com.example.bpm_player.databinding.ItemStemBinding
import com.example.bpm_player.stem.StemControl
import com.example.bpm_player.stem.StemMixer
import com.example.bpm_player.stem.StemSeparator
import com.example.bpm_player.stem.StemState
import com.example.bpm_player.stem.StemsFile
import com.example.bpm_player.stem.effectiveGains
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainStemkitBinding
    private var exoPlayer: ExoPlayer? = null
    private var detectedBpm: Float = 0f
    private var isRepeating = false
    private var isLoopingAll = true
    private var selectedSongUri: Uri? = null
    private var playFirstWhenLoaded = false
    private var playingSongUri: Uri? = null
    private var currentArtist: String? = null
    private lateinit var songAdapter: SongAdapter
    private var sortMode: SortMode = SortMode.TITLE
    private var itemTouchHelper: ItemTouchHelper? = null

    // Elementos (stems): o player toca o WAV de 8 canais e o StemMixer aplica solo/mudo/volume.
    private var stemMixer: StemMixer? = null
    private var playingStems: StemsFile? = null
    private val stemControls = MutableList(StemMixer.STEMS) { StemControl() }
    private val stemRows = arrayOfNulls<ItemStemBinding>(StemMixer.STEMS)

    // Estrutura (seções, frases, contagem 1–8): calculada offline por música; em tempo real só a posição é lida.
    // Só pelo botão Analisar; o resultado fica salvo no aparelho (StructureStore) e abre na hora nas próximas vezes.
    private var structure: StructureAnalyzer.SongStructure? = null
    private var structureBpm = 0f // BPM confiável da música atual; 0 = ainda não detectado (sem ele não dá para analisar)
    private var structureFailed = false
    private var structureBusyUri: Uri? = null // uma análise por vez; continua mesmo se a música mudar
    private var loopPhrase: IntRange? = null

    private val bpmHoldHandler = Handler(Looper.getMainLooper())
    private var bpmHoldRunnable: Runnable? = null
    private var bpmHoldDelta = 0f

    private fun createBpmHoldTouchListener(delta: Float): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    adjustBpm(delta)
                    bpmHoldDelta = delta
                    val runnable = object : Runnable {
                        override fun run() {
                            adjustBpm(bpmHoldDelta)
                            bpmHoldHandler.postDelayed(this, 60)
                        }
                    }
                    bpmHoldRunnable = runnable
                    bpmHoldHandler.postDelayed(runnable, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bpmHoldRunnable?.let { bpmHoldHandler.removeCallbacks(it) }
                    bpmHoldRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    enum class SortMode { TITLE, ARTIST, MANUAL }

    // Ordenação persistida no aparelho: modo escolhido + ordem manual (URIs, uma por linha)
    private val sortPrefs by lazy { getSharedPreferences("song_sort", MODE_PRIVATE) }

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

    // A 130 BPM um tempo dura 460 ms: a contagem precisa de ~25 quadros/s enquanto toca
    private val structureTicker = object : Runnable {
        override fun run() {
            updateStructureUi()
            if (exoPlayer?.isPlaying == true) handler.postDelayed(this, 40)
        }
    }

    data class Song(
        val uri: Uri,
        val title: String,
        val artist: String,
        val duration: Long,
        val filePath: String = ""
    )

    inner class SongAdapter : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

        private var songs: List<Song> = emptyList()
        private var selectedPosition: Int = RecyclerView.NO_POSITION
        private var playingPosition: Int = RecyclerView.NO_POSITION
        private var showDragHandle: Boolean = false

        private val colorAccent by lazy { ContextCompat.getColor(this@MainActivity, R.color.accent) }
        private val colorAccentLight by lazy { ContextCompat.getColor(this@MainActivity, R.color.accent_light) }
        private val colorTextPrimary by lazy { ContextCompat.getColor(this@MainActivity, R.color.text_primary) }
        private val colorTextSecondary by lazy { ContextCompat.getColor(this@MainActivity, R.color.text_secondary) }
        private val colorTextTertiary by lazy { ContextCompat.getColor(this@MainActivity, R.color.text_tertiary) }

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

                binding.songIcon.setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (isPlaying) {
                        exoPlayer?.pause()
                        playingSongUri = null
                        setPlayingPosition(RecyclerView.NO_POSITION)
                        binding.songIcon.announceForAccessibility(getString(R.string.cd_pause))
                    } else {
                        selectedSongUri = song.uri
                        setSelectedPosition(position)
                        setPlayingPosition(position)
                        onFilePicked(song.uri)
                        binding.songIcon.announceForAccessibility(getString(R.string.cd_play))
                    }
                }

                binding.root.setOnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showDeleteMenu(view, song, position)
                    true
                }

                if (isPlaying) {
                    binding.root.setBackgroundColor(colorAccentLight)
                    binding.songTitle.setTextColor(colorAccent)
                    binding.songArtist.setTextColor(colorAccent)
                    binding.songDuration.setTextColor(colorAccent)
                    binding.songIcon.setImageResource(R.drawable.ic_pause)
                    binding.songIcon.setColorFilter(colorAccent)
                } else if (isSelected) {
                    binding.root.setBackgroundColor(colorAccentLight)
                    binding.songTitle.setTextColor(colorTextPrimary)
                    binding.songArtist.setTextColor(colorTextSecondary)
                    binding.songDuration.setTextColor(colorTextSecondary)
                    binding.songIcon.setImageResource(R.drawable.ic_play)
                    binding.songIcon.setColorFilter(colorTextSecondary)
                } else {
                    binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    binding.songTitle.setTextColor(colorTextPrimary)
                    binding.songArtist.setTextColor(colorTextSecondary)
                    binding.songDuration.setTextColor(colorTextTertiary)
                    binding.songIcon.setImageResource(R.drawable.ic_music_note)
                    binding.songIcon.setColorFilter(colorTextTertiary)
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
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = songs.size
                override fun getNewListSize() = newSongs.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    songs[oldPos].uri == newSongs[newPos].uri
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    songs[oldPos] == newSongs[newPos]
            })
            songs = newSongs
            diff.dispatchUpdatesTo(this)
        }

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
        Log.e("BPM_DEBUG", "=== onCreate START ===")
        try {
            super.onCreate(savedInstanceState)
            Log.e("BPM_DEBUG", "super.onCreate OK")
            binding = ActivityMainStemkitBinding.inflate(layoutInflater)
            Log.e("BPM_DEBUG", "binding inflate OK")
            setContentView(binding.root)
            Log.e("BPM_DEBUG", "setContentView OK")

            setupSongSheet()
            setupRecyclerViews()
            binding.sortLabel.setOnClickListener { showSortDialog() }
            applySortMode(runCatching { SortMode.valueOf(sortPrefs.getString("mode", null)!!) }.getOrDefault(SortMode.TITLE))
            setupPlayerControls()
            setupBpmControls()
            setupElements()
            setupStructure()
            binding.toolbar.menu.add(R.string.settings_title).setOnMenuItemClickListener {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            Ads.setup(this, binding.adContainer, binding.toolbar)

            Log.e("BPM_DEBUG", "=== onCreate PHASE 1 OK ===")
        } catch (e: Exception) {
            Log.e("BPM_CRASH", "onCreate PHASE 1 CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        try {
            intent?.let { handleViewIntent(it) }
        } catch (e: Exception) {
            Log.e("BPM_CRASH", "handleViewIntent CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        try {
            ensureAudioPermissionAndLoad()
            applyBpm(binding.bpmSlider.value)
            setRepeatVisualState()
            updateLoopAllVisual()

            // Edge-to-edge: main content draws behind status bar, bottom bar above nav bar
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            // Conteúdo: inset da status bar em cima; o rodapé fica com o da navigation bar
            ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                binding.mainContent.setPadding(statusBars.left, statusBars.top, statusBars.right, 0)
                binding.bottomBar.setPadding(navBars.left, 0, navBars.right, max(navBars.bottom, ime.bottom))
                // painel aberto para logo abaixo da toolbar (margem no painel quebra o BottomSheetBehavior)
                BottomSheetBehavior.from(binding.songSheet).expandedOffset = statusBars.top + binding.toolbar.layoutParams.height
                insets
            }
            
            Log.e("BPM_DEBUG", "=== onCreate END SUCCESS ===")
        } catch (e: Exception) {
            Log.e("BPM_CRASH", "onCreate PHASE 2 CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Lista de músicas num painel inferior arrastável (padrão "A seguir" do YouTube Music / fila do Apple Music):
     * recolhido mostra só o cabeçalho acima do rodapé; toque ou arraste abre; "voltar" recolhe.
     */
    private fun setupSongSheet() {
        val sheet = BottomSheetBehavior.from(binding.songSheet)
        sheet.isFitToContents = false // abre até o expandedOffset (abaixo da toolbar); meio aberto também vale
        val peek = resources.getDimensionPixelSize(R.dimen.sheet_peek)
        val spacer = resources.getDimensionPixelSize(R.dimen.footer_spacer)
        val listPad = resources.getDimensionPixelSize(R.dimen.card_margin_top)
        // O rodapé fica por cima do painel: o recolhido aparece logo acima dele e nada fica escondido atrás.
        // Roda fora do passe de layout (mudar o peek durante o layout é ignorado) e a cada mudança de altura do rodapé.
        var lastBarH = -1
        binding.bottomBar.viewTreeObserver.addOnGlobalLayoutListener {
            val barH = binding.bottomBar.height
            if (barH == 0 || barH == lastBarH) return@addOnGlobalLayoutListener
            lastBarH = barH
            binding.bottomBar.post {
                sheet.peekHeight = peek + barH
                binding.songList.updatePadding(bottom = barH + listPad)
                binding.scroll.getChildAt(0).updatePadding(bottom = spacer + peek + barH)
            }
        }
        binding.songSheetHeader.setOnClickListener {
            sheet.state = if (sheet.state == BottomSheetBehavior.STATE_EXPANDED) BottomSheetBehavior.STATE_COLLAPSED
            else BottomSheetBehavior.STATE_EXPANDED
        }
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { sheet.state = BottomSheetBehavior.STATE_COLLAPSED }
        }
        onBackPressedDispatcher.addCallback(this, back)
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(view: View, state: Int) {
                back.isEnabled = state == BottomSheetBehavior.STATE_EXPANDED
                binding.songSheetHeader.contentDescription = getString(
                    if (state == BottomSheetBehavior.STATE_EXPANDED) R.string.cd_songs_collapse else R.string.cd_songs_expand
                )
            }
            override fun onSlide(view: View, offset: Float) = Unit
        })
    }

    private fun setupRecyclerViews() {
        songAdapter = SongAdapter()
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = songAdapter
        binding.songList.setHasFixedSize(true)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            // toque único já toca: sem esperar o tempo do duplo toque
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleRecyclerViewTap(e)
                return true
            }
        })

        binding.songList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                gestureDetector.onTouchEvent(e)
            }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (sortMode != SortMode.MANUAL) return false
                songAdapter.moveItem(vh.adapterPosition, target.adapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val songs = songAdapter.songsList().toMutableList()
                if (pos < 0 || pos >= songs.size) return
                val removed = songs.removeAt(pos)
                songAdapter.setSongs(songs)
                if (removed.uri == playingSongUri) {
                    exoPlayer?.pause()
                    playingSongUri = null
                    selectedSongUri = null
                    binding.trackTitle.text = getString(R.string.empty_title)
                    binding.trackStatus.text = getString(R.string.empty_hint)
                    binding.trackTimeCurrent.text = "0:00"
                    binding.trackTimeTotal.text = "--:--"
                    binding.trackSlider.value = 0f
                    binding.trackSlider.isEnabled = false
                    handler.removeCallbacks(positionUpdater)
                }
                if (removed.uri == selectedSongUri) {
                    selectedSongUri = null
                }
                updateEmptyState()
            }
            override fun isLongPressDragEnabled(): Boolean = sortMode == SortMode.MANUAL
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (sortMode == SortMode.MANUAL) saveManualOrder()
            }
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.4f
            override fun onChildDraw(c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = vh.itemView
                    val icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)
                    icon?.let {
                        val iconMargin = (itemView.height - it.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + it.intrinsicHeight
                        if (dX > 0) {
                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + it.intrinsicWidth
                            it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            it.setTint(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                        }
                        val bg = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_swipe_delete)
                        bg?.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                        bg?.draw(c)
                        if (dX > iconMargin + it.intrinsicWidth) {
                            it.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        })
        itemTouchHelper?.attachToRecyclerView(binding.songList)
    }

    private fun setupPlayerControls() {
        binding.selectButton.setOnClickListener { checkPermissionAndPickFile() }
        binding.repeatButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleRepeat()
        }
        binding.loopAllButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleLoopAll()
        }
        binding.playFab.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayPause()
        }
        binding.skipPreviousButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            playPreviousInQueue()
        }
        binding.skipNextButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            playNextInQueue()
        }
        binding.trackSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val targetMs = (value / 1000f * (exoPlayer?.duration ?: 0)).toLong()
                exoPlayer?.seekTo(targetMs)
            }
        }
    }

    private fun setupBpmControls() {
        binding.bpmSlider.setLabelFormatter { value ->
            String.format(Locale.US, "%.0f BPM", value)
        }
        binding.bpmSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) applyBpm(value)
        }
        binding.bpmMinusButton.setOnTouchListener(createBpmHoldTouchListener(-1f))
        binding.bpmPlusButton.setOnTouchListener(createBpmHoldTouchListener(1f))
        binding.bpmResetButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val reference = if (detectedBpm > 0f) detectedBpm else binding.bpmSlider.value
            binding.bpmSlider.value = reference.toInt().coerceIn(40, 200).toFloat()
            applyBpm(reference)
        }
    }

    override fun onResume() {
        super.onResume()
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
        if (isRepeating) isLoopingAll = false
        exoPlayer?.repeatMode = if (isRepeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        setRepeatVisualState()
        updateLoopAllVisual()
    }

    private fun toggleLoopAll() {
        isLoopingAll = !isLoopingAll
        if (isLoopingAll) isRepeating = false
        setRepeatVisualState()
        updateLoopAllVisual()
    }

    private fun updateLoopAllVisual() {
        binding.loopAllButton.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isLoopingAll) R.color.accent else R.color.text_secondary)
        )
    }

    private fun setRepeatVisualState() {
        binding.repeatButton.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isRepeating) R.color.accent else R.color.text_secondary)
        )
    }

    private fun applyBpm(targetBpm: Float) {
        binding.bpmValue.text = String.format(Locale.US, "%.0f", targetBpm)
        pulseBpmValue()
        val referenceBpm = if (detectedBpm > 0f) detectedBpm else targetBpm
        val speed = (targetBpm / referenceBpm).coerceIn(0.25f, 4.0f)
        exoPlayer?.setPlaybackSpeed(speed)
        if (detectedBpm > 0f) {
            binding.trackStatus.text = getString(R.string.status_bpm_detected, detectedBpm, targetBpm)
        } else {
            binding.trackStatus.text = getString(R.string.status_bpm_manual, targetBpm)
        }
        val description = when {
            speed > 1.01f -> "mais rápido"
            speed < 0.99f -> "mais lento"
            else -> "tempo original"
        }
        binding.tempoFactorValue.text = String.format(Locale.US, "%.2f× · %s", speed, description)
    }

    private fun pulseBpmValue() {
        ObjectAnimator.ofFloat(binding.bpmValue, "scaleX", 1f, 1.05f, 1f).apply { duration = 180; start() }
        ObjectAnimator.ofFloat(binding.bpmValue, "scaleY", 1f, 1.05f, 1f).apply { duration = 180; start() }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            1001 -> if (granted) filePickerLauncher.launch("audio/*")
            1002 -> if (granted) {
                loadSongs()
            } else if (playFirstWhenLoaded) {
                playFirstWhenLoaded = false
                filePickerLauncher.launch("audio/*") // o seletor do sistema não precisa da permissão
            }
            1003 -> { }
        }
    }

    private fun onFilePicked(uri: Uri) {
        val sameSong = uri == playingSongUri // "tocar todas" com uma faixa só recomeça a mesma música: mantém a mesa
        handler.removeCallbacks(positionUpdater)
        handler.removeCallbacks(structureTicker)
        playingSongUri = uri
        ensureSongInList(uri)
        binding.trackTitle.text = resolveTrackTitle(uri)
        binding.trackStatus.text = getString(R.string.status_analyzing)
        binding.trackSlider.value = 0f
        binding.trackSlider.isEnabled = false
        binding.trackTimeCurrent.text = "0:00"
        binding.trackTimeTotal.text = "--:--"
        detectedBpm = 0f
        currentArtist = null
        exoPlayer?.release()
        exoPlayer = null
        // Trocou de música: a separação em andamento (de outra faixa) continua; só o resultado pronto volta ao repouso.
        StemSeparator.reset()
        if (!sameSong) stemControls.indices.forEach { stemControls[it] = StemControl() }

        startPlayback(uri)
        structureBpm = 0f
        structureFailed = false
        showStructure(null)
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = StructureStore.load(applicationContext, uri)
            withContext(Dispatchers.Main) {
                if (saved != null && !isDestroyed && uri == playingSongUri) showStructure(saved)
            }
        }
        stemControls.indices.forEach { renderStemRow(it) }
        renderElements()

        lifecycleScope.launch(Dispatchers.IO) {
            var pcm: AudioDecoder.PcmData? = null
            try {
                pcm = AudioDecoder.decode(applicationContext, uri)
            } catch (e: Exception) {
                Log.e("BPM_DECODE", "onFilePicked decode exception: ${e.javaClass.simpleName}: ${e.message}")
            }
            val result = pcm?.let { BpmDetector.detect(it.samples, it.sampleRate) }
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing || uri != playingSongUri) return@withContext
                if (result != null && result.confidence > 0f) {
                    structureBpm = result.bpm
                    renderStructureCard()
                    detectedBpm = result.bpm
                    binding.bpmSlider.value = (result.bpm + 0.5f).toInt().coerceIn(40, 200).toFloat()
                    applyBpm(result.bpm)
                    binding.trackStatus.text = getString(R.string.status_bpm_detected, result.bpm, binding.bpmSlider.value)
                } else {
                    structureFailed = true // sem batida clara não há grade; a análise salva, se houver, continua valendo
                    renderStructureCard()
                    detectedBpm = if (binding.bpmSlider.value > 0f) binding.bpmSlider.value else 100f
                    applyBpm(detectedBpm)
                    binding.trackStatus.text = getString(R.string.toast_no_bpm)
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val fallbackTitle = withContext(Dispatchers.Main) { resolveTrackTitle(uri) }
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaArtistName = metaArtist?.trim().orEmpty().ifEmpty { metaAlbum?.trim().orEmpty() }
                val displayTitle = metaTitle?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
                val finalArtist = metaArtistName
                withContext(Dispatchers.Main) {
                    binding.trackTitle.text = displayTitle
                    currentArtist = finalArtist
                    if (currentArtist?.isNotEmpty() == true) {
                        binding.trackStatus.text = getString(R.string.artist_format, finalArtist, getString(R.string.status_analyzing))
                    }
                }
                retriever.release()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.trackTitle.text = fallbackTitle
                }
            }
        }
    }

    private fun startPlayback(uri: Uri) {
        if (uri != playingSongUri) return
        try {
            val stems = if (StemSeparator.isSupported(this)) StemSeparator.cached(this, uri) else null
            playingStems = stems
            exoPlayer = buildPlayer().also { player ->
                player.repeatMode = if (isRepeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.setMediaItem(MediaItem.fromUri(stems?.wav ?: uri))
                player.prepare()
                player.playWhenReady = true
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val currentTitle = binding.trackTitle.text.toString()
                        if (currentTitle.isBlank() || currentTitle == getString(R.string.empty_title)) {
                            playingSongUri?.let { binding.trackTitle.text = resolveTrackTitle(it) }
                        }
                        updatePlayFab(isPlaying)
                        handler.removeCallbacks(structureTicker)
                        if (isPlaying) handler.post(structureTicker) else updateStructureUi()
                        val playingIndex = songAdapter.songsList().indexOfFirst { it.uri == playingSongUri }
                        if (isPlaying) {
                            if (playingIndex >= 0) songAdapter.setPlayingPosition(playingIndex)
                        } else {
                            songAdapter.setPlayingPosition(RecyclerView.NO_POSITION)
                        }
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                binding.trackSlider.isEnabled = true
                                binding.trackTimeTotal.text = formatTime(player.duration)
                                val currentTitle = binding.trackTitle.text.toString()
                                if (currentTitle.isBlank() || currentTitle == getString(R.string.empty_title)) {
                                    playingSongUri?.let { binding.trackTitle.text = resolveTrackTitle(it) }
                                }
                                applyBpm(binding.bpmSlider.value)
                                handler.post(positionUpdater)
                            }
                            Player.STATE_ENDED -> {
                                if (player.repeatMode == Player.REPEAT_MODE_OFF) {
                                    val advanced = playNextInQueue()
                                    if (!advanced) {
                                        player.playWhenReady = false
                                        player.pause()
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
                        Log.e("BPM_PLAY", "PlaybackException: code=${error.errorCode}, name=${error.errorCodeName}, msg=${error.message}")
                        binding.trackStatus.text = getString(R.string.toast_no_bpm)
                        Snackbar.make(binding.root, "Falha ao reproduzir: ${error.errorCodeName}", Snackbar.LENGTH_LONG).show()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("BPM_PLAY", "startPlayback falhou: ${e.javaClass.simpleName}: ${e.message}")
            binding.trackStatus.text = "Falha ao iniciar player"
            Snackbar.make(binding.root, "Falha ao reproduzir: ${e.javaClass.simpleName}", Snackbar.LENGTH_LONG).show()
        }
    }

    /** ExoPlayer com o StemMixer na cadeia de áudio (antes do Sonic, que faz o BPM). Inativo para arquivos comuns. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun buildPlayer(): ExoPlayer {
        val mixer = StemMixer().also { it.gains = effectiveGains(stemControls) }
        stemMixer = mixer
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(mixer))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        return ExoPlayer.Builder(this, renderers).build()
    }

    // ───────────────────────── Estrutura (DESIGN_SYSTEM 4.13) ─────────────────────────

    private fun setupStructure() {
        binding.structureBar.onSeek = { ms ->
            loopPhrase = null
            renderPhraseLoop()
            exoPlayer?.seekTo(ms)
            updateStructureUi()
        }
        binding.phrasePrevButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            jumpPhrase(-1)
        }
        binding.phraseNextButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            jumpPhrase(+1)
        }
        binding.phraseLoopButton.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val s = structure
            val player = exoPlayer
            loopPhrase = if (loopPhrase != null || s == null || player == null) null else s.phraseAt(s.beatAt(player.currentPosition))
            renderPhraseLoop()
        }
        binding.structureAnalyze.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            analyzeStructure()
        }
        binding.structureDelete.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val uri = playingSongUri ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) { StructureStore.delete(applicationContext, uri) }
            structureFailed = false
            showStructure(null)
            Snackbar.make(binding.root, R.string.structure_deleted, Snackbar.LENGTH_SHORT).show()
        }
    }

    /** Decodifica a música inteira, analisa e salva no aparelho. Termina mesmo se a música mudar no meio. */
    private fun analyzeStructure() {
        val uri = playingSongUri ?: return
        val bpm = structureBpm
        if (bpm <= 0f || structureBusyUri != null) return
        structureBusyUri = uri
        renderStructureCard()
        lifecycleScope.launch(Dispatchers.IO) {
            val found = runCatching {
                AudioDecoder.decode(applicationContext, uri, 0L)?.let { StructureAnalyzer.analyze(it.samples, it.sampleRate, bpm) }
            }.onFailure { Log.e("BPM_STRUCT", "análise falhou: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            found?.let { runCatching { StructureStore.save(applicationContext, uri, it) } }
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                structureBusyUri = null
                if (uri == playingSongUri) {
                    structureFailed = found == null
                    showStructure(found)
                } else renderStructureCard()
            }
        }
    }

    private fun showStructure(s: StructureAnalyzer.SongStructure?) {
        structure = s
        loopPhrase = null
        renderPhraseLoop()
        binding.structureBar.structure = s
        renderStructureCard()
        handler.removeCallbacks(structureTicker)
        if (exoPlayer?.isPlaying == true) handler.post(structureTicker) else updateStructureUi()
    }

    /** Estados do cartão (DESIGN_SYSTEM 4.13): aguardando BPM, pronto para analisar, analisando, outra em análise, sem estrutura, salva. */
    private fun renderStructureCard() {
        val s = structure
        val uri = playingSongUri
        val busy = structureBusyUri
        binding.structureCard.visibility = if (uri != null) View.VISIBLE else View.GONE
        binding.structureStatus.setText(when {
            s != null -> R.string.structure_hint
            busy != null && busy == uri -> R.string.structure_analyzing
            busy != null -> R.string.structure_busy_other
            structureFailed -> R.string.structure_none
            structureBpm <= 0f -> R.string.structure_wait_bpm
            else -> R.string.structure_idle
        })
        binding.structureProgress.visibility = if (busy != null && busy == uri) View.VISIBLE else View.GONE
        val ready = if (s != null) View.VISIBLE else View.GONE
        binding.structureBar.visibility = ready
        binding.structureLive.visibility = ready
        binding.structureDelete.visibility = ready
        binding.structureAnalyze.visibility = if (s == null) View.VISIBLE else View.GONE
        binding.structureAnalyze.isEnabled = busy == null && structureBpm > 0f
        binding.structureAnalyze.setText(if (structureFailed) R.string.btn_retry else R.string.btn_analyze)
    }

    /** Barra, seção, frase e contagem na posição atual; com o loop de frase ligado, volta ao início ao passar do fim. */
    private fun updateStructureUi() {
        val s = structure ?: return
        val player = exoPlayer ?: return
        val duration = player.duration.coerceAtLeast(0L)
        var pos = player.currentPosition
        loopPhrase?.let { r ->
            val start = if (r.first == 0) 0L else s.beatStartMs(r.first)
            val end = s.beatStartMs(r.last + 1).let { if (duration > 0) minOf(it, duration - 100) else it }
            if (pos >= end) { player.seekTo(start); pos = start }
        }
        binding.structureBar.durationMs = duration
        binding.structureBar.positionMs = pos
        val beat = s.beatAt(pos)
        val section = s.sections[s.sectionIndexAt(beat)]
        binding.sectionName.setText(section.kind.labelRes())
        binding.sectionName.setTextColor(ContextCompat.getColor(this, section.kind.colorRes()))
        val starts = s.phraseStarts(section)
        binding.phraseLabel.text = getString(R.string.structure_phrase, starts.indexOfLast { beat >= it } + 1, starts.size)
        binding.countView.count = if (pos < s.firstBeatMs) 0 else s.count(beat)
    }

    /** Frase seguinte; para trás, volta ao início da atual ou, se já está nele, à anterior (padrão de player). */
    private fun jumpPhrase(direction: Int) {
        val s = structure ?: return
        val player = exoPlayer ?: return
        val beat = s.beatAt(player.currentPosition)
        val starts = s.allPhraseStarts()
        val cur = starts.indexOfLast { beat >= it }.coerceAtLeast(0)
        val target = if (direction > 0) cur + 1 else if (beat - starts[cur] >= 2) cur else (cur - 1).coerceAtLeast(0)
        val startBeat = starts.getOrNull(target) ?: return
        if (loopPhrase != null) loopPhrase = s.phraseAt(startBeat)
        player.seekTo(if (target == 0) 0L else s.beatStartMs(startBeat))
        updateStructureUi()
    }

    private fun renderPhraseLoop() {
        val on = loopPhrase != null
        binding.phraseLoopButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.accent else R.color.text_secondary))
        binding.phraseLoopButton.contentDescription = getString(if (on) R.string.cd_phrase_loop_off else R.string.cd_phrase_loop)
    }

    // ───────────────────────── Elementos (stems) ─────────────────────────

    private fun StemState.belongsTo(): Uri? = when (this) {
        is StemState.Downloading -> uri
        is StemState.Separating -> uri
        is StemState.Ready -> uri
        is StemState.Failed -> uri
        StemState.Idle -> null
    }

    private fun setupElements() {
        // Ordem na tela: voz primeiro. Índices seguem o WAV: drums, bass, other, vocals.
        val names = intArrayOf(R.string.stem_drums, R.string.stem_bass, R.string.stem_other, R.string.stem_vocals)
        val icons = intArrayOf(R.drawable.ic_stem_drums, R.drawable.ic_stem_bass, R.drawable.ic_music_note, R.drawable.ic_stem_vocals)
        for (stem in intArrayOf(3, 0, 1, 2)) {
            val row = ItemStemBinding.inflate(layoutInflater, binding.stemRows, true)
            val name = getString(names[stem])
            row.stemIcon.setImageResource(icons[stem])
            row.stemName.text = name
            row.stemVolume.contentDescription = getString(R.string.cd_stem_volume, name)
            row.stemVolume.addOnChangeListener { _, value, fromUser ->
                if (fromUser) updateStem(stem) { it.copy(volume = value / 100f) }
            }
            row.stemSolo.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateStem(stem) { it.copy(solo = !it.solo) }
            }
            row.stemMute.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updateStem(stem) { it.copy(muted = !it.muted) }
            }
            stemRows[stem] = row
            renderStemRow(stem)
        }
        binding.elementsAction.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val uri = playingSongUri ?: return@setOnClickListener
            when (StemSeparator.state.value) {
                is StemState.Downloading, is StemState.Separating -> StemSeparator.cancel()
                else -> StemSeparator.start(this, uri)
            }
        }
        binding.elementsClear.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            clearElements()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StemSeparator.state.collect { onStemState(it) }
            }
        }
    }

    private fun updateStem(stem: Int, change: (StemControl) -> StemControl) {
        stemControls[stem] = change(stemControls[stem])
        stemMixer?.gains = effectiveGains(stemControls)
        renderStemRow(stem)
    }

    private fun renderStemRow(stem: Int) {
        val row = stemRows[stem] ?: return
        val c = stemControls[stem]
        val name = row.stemName.text
        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.text_secondary)
        row.stemVolume.value = (c.volume * 100f).coerceIn(0f, 100f)
        row.stemSolo.iconTint = ColorStateList.valueOf(if (c.solo) on else off)
        row.stemSolo.contentDescription = getString(if (c.solo) R.string.cd_stem_unsolo else R.string.cd_stem_solo, name)
        row.stemMute.setIconResource(if (c.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        row.stemMute.iconTint = ColorStateList.valueOf(if (c.muted) on else off)
        row.stemMute.contentDescription = getString(if (c.muted) R.string.cd_stem_unmute else R.string.cd_stem_mute, name)
    }

    private fun onStemState(state: StemState) {
        if (state is StemState.Ready && state.uri == playingSongUri && playingStems == null) switchSource(state.stems)
        renderElements(state)
    }

    /** Troca a fonte do player (WAV de stems ou arquivo original) mantendo posição e play/pause. */
    private fun switchSource(stems: StemsFile?) {
        val player = exoPlayer ?: return
        val uri = playingSongUri ?: return
        val position = player.currentPosition
        val play = player.playWhenReady
        playingStems = stems
        player.setMediaItem(MediaItem.fromUri(stems?.wav ?: uri), position)
        player.prepare()
        player.playWhenReady = play
    }

    /** Apaga os elementos da música que está tocando e volta ao áudio original. */
    private fun clearElements() {
        val uri = playingSongUri ?: return
        switchSource(null) // solta o WAV antes de apagar
        stemControls.indices.forEach { stemControls[it] = StemControl() }
        stemControls.indices.forEach { renderStemRow(it) }
        renderElements()
        lifecycleScope.launch(Dispatchers.IO) {
            StemSeparator.delete(applicationContext, uri)
            withContext(Dispatchers.Main) {
                renderElements()
                Snackbar.make(binding.root, R.string.elements_cleared, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderElements(state: StemState = StemSeparator.state.value) {
        val uri = playingSongUri
        binding.elementsCard.visibility = if (uri == null) View.GONE else View.VISIBLE
        if (uri == null) return
        val mine = state.belongsTo() == uri
        val busyElsewhere = !mine && (state is StemState.Downloading || state is StemState.Separating)
        val stems = playingStems
        binding.stemRows.visibility = if (stems != null) View.VISIBLE else View.GONE
        stems?.let { s -> stemRows.forEachIndexed { i, row -> row?.root?.visibility = if (s.silent[i]) View.GONE else View.VISIBLE } }
        binding.elementsProgress.visibility = View.GONE
        binding.elementsAction.visibility = View.VISIBLE
        binding.elementsAction.isEnabled = !busyElsewhere
        binding.elementsClear.visibility = if (stems != null) View.VISIBLE else View.GONE
        when {
            !StemSeparator.isSupported(this) -> {
                binding.elementsStatus.text = getString(R.string.elements_unsupported)
                binding.elementsAction.visibility = View.GONE
            }
            stems != null -> {
                binding.elementsStatus.text = getString(R.string.elements_ready)
                binding.elementsAction.visibility = View.GONE
            }
            mine && state is StemState.Downloading -> showProgress(getString(R.string.elements_downloading, state.percent), state.percent)
            mine && state is StemState.Separating -> showProgress(getString(R.string.elements_separating, state.percent), state.percent)
            busyElsewhere -> {
                val pct = (state as? StemState.Separating)?.percent ?: (state as? StemState.Downloading)?.percent ?: 0
                binding.elementsStatus.text = getString(R.string.elements_busy_other, pct)
                binding.elementsAction.text = getString(R.string.btn_separate)
            }
            mine && state is StemState.Failed -> {
                binding.elementsStatus.text = getString(R.string.elements_failed, state.reason)
                binding.elementsAction.text = getString(R.string.btn_retry)
            }
            StemSeparator.isModelReady(this) -> {
                binding.elementsStatus.text = getString(R.string.elements_hint)
                binding.elementsAction.text = getString(R.string.btn_separate)
            }
            else -> {
                binding.elementsStatus.text = getString(R.string.elements_need_model, (StemSeparator.MODEL_BYTES / (1024 * 1024)).toInt())
                binding.elementsAction.text = getString(R.string.btn_download_separate)
            }
        }
    }

    private fun showProgress(text: String, percent: Int) {
        binding.elementsStatus.text = text
        binding.elementsProgress.visibility = View.VISIBLE
        binding.elementsProgress.setProgressCompat(percent, true)
        binding.elementsAction.text = getString(R.string.btn_cancel)
    }

    private fun updatePlayFab(isPlaying: Boolean) {
        binding.playFab.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playFab.contentDescription = if (isPlaying) getString(R.string.cd_pause) else getString(R.string.cd_play)
    }

    private fun togglePlayPause() {
        val exo = exoPlayer
        when {
            exo == null && playingSongUri == null -> {
                if (!playSelectedOrFirst()) {
                    // Lista ainda vazia (sem permissão ou carregando): carrega e toca a primeira ao terminar
                    playFirstWhenLoaded = true
                    ensureAudioPermissionAndLoad()
                }
            }
            exo?.isPlaying == true -> {
                exo.pause()
            }
            else -> {
                if (exo?.playbackState == Player.STATE_ENDED) {
                    exo.seekTo(0L)
                }
                exo?.play()
            }
        }
    }

    private fun playSelectedOrFirst(): Boolean {
        val songs = songAdapter.songsList()
        val targetUri = selectedSongUri?.takeIf { songs.any { s -> s.uri == it } }
            ?: songs.firstOrNull()?.uri
            ?: return false
        selectedSongUri = targetUri
        val idx = songs.indexOfFirst { it.uri == targetUri }
        if (idx >= 0) {
            songAdapter.setSelectedPosition(idx)
            songAdapter.setPlayingPosition(idx)
        }
        onFilePicked(targetUri)
        return true
    }

    private fun resolveTrackTitle(uri: Uri): String {
        songAdapter.songsList().firstOrNull { it.uri == uri }?.title?.let { return it }
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
        } catch (_: Exception) { }
        return stripExtension(uri.lastPathSegment ?: uri.toString())
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun ensureSongInList(uri: Uri) {
        val existingIndex = songAdapter.songsList().indexOfFirst { it.uri == uri }
        if (existingIndex >= 0) {
            songAdapter.setSelectedPosition(existingIndex)
            songAdapter.setPlayingPosition(existingIndex)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var title = ""
            var artist = ""
            var duration = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                    .ifEmpty { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty() }
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durStr?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (_: Exception) { }
            if (title.isEmpty()) title = resolveTrackTitle(uri)
            if (artist == "<unknown>") artist = ""
            withContext(Dispatchers.Main) {
                val songs = songAdapter.songsList().toMutableList()
                if (songs.any { it.uri == uri }) {
                    val idx = songs.indexOfFirst { it.uri == uri }
                    songAdapter.setSelectedPosition(idx)
                    songAdapter.setPlayingPosition(idx)
                    return@withContext
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
        }
    }

    private fun playPreviousInQueue(): Boolean {
        val current = playingSongUri ?: return false
        val songs = songAdapter.songsList()
        if (songs.isEmpty()) return false
        val idx = songs.indexOfFirst { it.uri == current }
        if (idx < 0) return false
        val prevIdx = when {
            idx > 0 -> idx - 1
            isLoopingAll -> songs.size - 1
            else -> return false
        }
        val prev = songs[prevIdx]
        selectedSongUri = prev.uri
        songAdapter.setSelectedPosition(prevIdx)
        songAdapter.setPlayingPosition(prevIdx)
        onFilePicked(prev.uri)
        return true
    }

    private fun playNextInQueue(): Boolean {
        val current = playingSongUri ?: return false
        val songs = songAdapter.songsList()
        if (songs.isEmpty()) return false
        val idx = songs.indexOfFirst { it.uri == current }
        if (idx < 0) return false
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
        lifecycleScope.launch(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            val seenPaths = mutableSetOf<String>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.MIME_TYPE} != 'audio/opus'"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
            try {
                Log.i("BPM_MUSIC", "Iniciando query MediaStore: $selection")
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, null, sortOrder
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
                        if (filePath.isNotBlank() && isAudioFromMessagingApp(filePath)) {
                            Log.d("BPM_MUSIC", "Ignorado (messaging): $title | $filePath")
                        } else {
                            val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                            if (filePath.isNotBlank()) seenPaths.add(filePath)
                            songs.add(Song(uri, title, cleanArtist, duration, filePath))
                            Log.d("BPM_MUSIC", "MediaStore: $title | $filePath")
                        }
                    }
                    Log.i("BPM_MUSIC", "MediaStore retornou ${songs.size} músicas")
                }
            } catch (e: Exception) {
                Log.e("BPM_MUSIC", "Erro MediaStore: ${e.message}")
            }

            val storageDirs = mutableListOf<File>()
            try {
                val ext = android.os.Environment.getExternalStorageDirectory()
                if (ext != null && ext.exists()) storageDirs.add(ext)
            } catch (_: Exception) { }
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
                    val countBefore = songs.size
                    dir.walkTopDown().maxDepth(6).filter { f ->
                        f.isFile && !isIgnoredAudioFile(f.absolutePath)
                    }.forEach audioEach@{ audioFile ->
                        if (seenPaths.contains(audioFile.absolutePath)) return@audioEach
                        val path = audioFile.absolutePath
                        val title = audioFile.nameWithoutExtension
                        val uri = Uri.fromFile(audioFile)
                        seenPaths.add(path)
                        songs.add(Song(uri, title, "", 0L, path))
                    }
                    val added = songs.size - countBefore
                    if (added > 0) Log.i("BPM_MUSIC", "Varredura $dir: +$added música(s)")
                } catch (e: Exception) {
                    Log.e("BPM_MUSIC", "Erro varredura ${dir.absolutePath}: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                val sorted = applySortInPlace(songs.toMutableList())
                Log.i("BPM_MUSIC", "Lista final: ${sorted.size} música(s)")
                songAdapter.setSongs(sorted)
                updateEmptyState()
                if (sorted.isNotEmpty()) {
                    binding.trackStatus.text = "${sorted.size} música(s) encontrada(s)"
                } else {
                    binding.trackStatus.text = "Nenhuma música encontrada (verifique permissão de áudio)"
                }
                if (playFirstWhenLoaded) {
                    playFirstWhenLoaded = false
                    if (exoPlayer == null && playingSongUri == null && !playSelectedOrFirst()) {
                        filePickerLauncher.launch("audio/*")
                    }
                }
            }
        }
    }

    private fun isAudioFromMessagingApp(filePath: String): Boolean {
        val path = filePath.lowercase()
        return path.contains("/whatsapp/") ||
                path.contains("/telegram/") ||
                path.contains("/signal/") ||
                path.contains("/messenger/") ||
                path.contains("/discord/")
    }

    private fun isIgnoredAudioFile(filePath: String): Boolean {
        if (isAudioFromMessagingApp(filePath)) return true
        val ext = filePath.substringAfterLast('.', "").lowercase()
        if (ext == "opus") return true
        return false
    }

    private fun applySortInPlace(list: MutableList<Song>): List<Song> {
        when (sortMode) {
            SortMode.TITLE -> list.sortBy { it.title.lowercase(Locale.getDefault()) }
            SortMode.ARTIST -> list.sortWith(compareBy({ it.artist.lowercase(Locale.getDefault()) }, { it.title.lowercase(Locale.getDefault()) }))
            SortMode.MANUAL -> {
                val order = sortPrefs.getString("manual_order", null)?.split('\n')
                    ?.withIndex()?.associate { it.value to it.index }.orEmpty()
                list.sortBy { order[it.uri.toString()] ?: Int.MAX_VALUE } // músicas novas vão para o fim
            }
        }
        return list
    }

    private fun saveManualOrder() {
        sortPrefs.edit().putString("manual_order", songAdapter.songsList().joinToString("\n") { it.uri.toString() }).apply()
    }

    private fun updateEmptyState() {
        val isEmpty = songAdapter.songsList().isEmpty()
        binding.songsTitle.text = getString(R.string.songs_title_count, songAdapter.songsList().size)
        if (isEmpty) {
            binding.emptySongList.text = if (hasAudioPermission()) getString(R.string.empty_song_list) else getString(R.string.empty_song_list_no_permission)
            binding.emptySongList.visibility = View.VISIBLE
            binding.songList.visibility = View.GONE
        } else {
            binding.emptySongList.visibility = View.GONE
            binding.songList.visibility = View.VISIBLE
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(getString(R.string.sort_title), getString(R.string.sort_artist), getString(R.string.sort_manual))
        AlertDialog.Builder(this)
            .setTitle(R.string.cd_sort)
            .setItems(labels) { _, which ->
                val newMode = when (which) { 0 -> SortMode.TITLE; 1 -> SortMode.ARTIST; else -> SortMode.MANUAL }
                applySortMode(newMode)
            }
            .show()
    }

    private fun applySortMode(newMode: SortMode) {
        sortMode = newMode
        sortPrefs.edit().putString("mode", newMode.name).apply()
        binding.sortLabel.text = when (newMode) {
            SortMode.TITLE -> getString(R.string.sort_title)
            SortMode.ARTIST -> getString(R.string.sort_artist)
            SortMode.MANUAL -> getString(R.string.sort_manual)
        }
        songAdapter.setShowDragHandle(newMode == SortMode.MANUAL)
        val current = songAdapter.songsList().toMutableList()
        val sorted = applySortInPlace(current)
        songAdapter.setSongs(sorted)
        val playingIndex = sorted.indexOfFirst { it.uri == playingSongUri }
        if (playingIndex >= 0) songAdapter.setPlayingPosition(playingIndex)
        val selectedIndex = sorted.indexOfFirst { it.uri == selectedSongUri }
        if (selectedIndex >= 0) songAdapter.setSelectedPosition(selectedIndex)
    }

    private fun handleRecyclerViewTap(e: MotionEvent) {
        val recyclerView = binding.songList
        val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return
        val song = songAdapter.getSongAt(position) ?: return
        // Um toque toca a música, como em qualquer player; tocar a que já está tocando só a mantém
        if (song.uri == playingSongUri) return
        selectedSongUri = song.uri
        songAdapter.setSelectedPosition(position)
        songAdapter.setPlayingPosition(position)
        onFilePicked(song.uri)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun showDeleteMenu(anchor: View, song: Song, position: Int) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_song_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> { confirmDeleteSong(song, position); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDeleteSong(song: Song, position: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_delete_confirm_title)
            .setMessage(getString(R.string.menu_delete_confirm_msg, song.title))
            .setPositiveButton(R.string.menu_delete_song) { _, _ -> deleteSong(song, position) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSong(song: Song, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = false
            try {
                val rows = contentResolver.delete(song.uri, null, null)
                deleted = rows > 0
            } catch (_: Exception) { }
            if (!deleted && song.filePath.isNotBlank()) {
                try {
                    val file = java.io.File(song.filePath)
                    if (file.exists() && file.delete()) deleted = true
                } catch (_: Exception) { }
            }
            withContext(Dispatchers.Main) {
                if (deleted) {
                    val songs = songAdapter.songsList().toMutableList()
                    songs.removeAt(position)
                    songAdapter.setSongs(songs)
                    if (song.uri == playingSongUri) {
                        exoPlayer?.pause()
                        playingSongUri = null
                        selectedSongUri = null
                        binding.trackTitle.text = getString(R.string.empty_title)
                        binding.trackStatus.text = getString(R.string.empty_hint)
                        binding.trackTimeCurrent.text = "0:00"
                        binding.trackTimeTotal.text = "--:--"
                        binding.trackSlider.value = 0f
                        binding.trackSlider.isEnabled = false
                        handler.removeCallbacks(positionUpdater)
                    }
                } else {
                    // Silenciado: exclusão sem toast
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleViewIntent(it) }
    }

    private fun handleViewIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW || intent.action == "com.example.bpm_player.TEST_PLAY") {
            val uriStr = intent.dataString ?: intent.getStringExtra("uri")
            if (uriStr != null) {
                val uri = Uri.parse(uriStr)
                Log.i("BPM_TEST", "handleViewIntent: tocando $uri")
                selectedSongUri = uri
                onFilePicked(uri)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(positionUpdater)
        handler.removeCallbacks(structureTicker)
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
