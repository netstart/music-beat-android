package com.example.bpm_player.audio

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller que expõe o estado do `AudioEngineKt` para a UI (Compose ou Views).
 *
 * **Por que esse design?**
 * - `StateFlow` é thread-safe, lifecycle-aware e compatível com Compose via `collectAsState()`.
 * - Mantém uma única fonte de verdade: o `AudioEngine` emite eventos, o controller atualiza o flow.
 * - Componentes podem observar `bpm`, `phase`, `isPlaying`, etc., sem polling.
 *
 * **Uso com Compose**:
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val controller = LocalBeatController.current
 *     val bpm by controller.bpm.collectAsState()
 *     val isPlaying by controller.isPlaying.collectAsState()
 *     // ...
 * }
 * ```
 *
 * **Uso com Views/XML**:
 * ```kotlin
 * lifecycleScope.launch {
 *     controller.bpm.collect { bpm -> textView.text = bpm.toInt().toString() }
 * }
 * ```
 */
class BeatUiController(
    private val engine: AudioEngineKt
) {

    // --- StateFlows observáveis pela UI ---

    private val _bpm = MutableStateFlow(120f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private val _beatPhase = MutableStateFlow(1)
    val beatPhase: StateFlow<Int> = _beatPhase.asStateFlow()

    private val _isDownbeat = MutableStateFlow(false)
    val isDownbeat: StateFlow<Boolean> = _isDownbeat.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _lowEnergy = MutableStateFlow(0f)
    val lowEnergy: StateFlow<Float> = _lowEnergy.asStateFlow()

    private val _midEnergy = MutableStateFlow(0f)
    val midEnergy: StateFlow<Float> = _midEnergy.asStateFlow()

    private val _highEnergy = MutableStateFlow(0f)
    val highEnergy: StateFlow<Float> = _highEnergy.asStateFlow()

    private val _latestEvent = MutableStateFlow<BeatEvent?>(null)
    val latestEvent: StateFlow<BeatEvent?> = _latestEvent.asStateFlow()

    init {
        // Conecta o engine: cada BeatEvent atualiza os StateFlows
        engine.setOnBeatEventListener { event ->
            _bpm.value = event.bpm
            _confidence.value = event.confidence
            _beatPhase.value = event.beatPhase
            _isDownbeat.value = event.isDownbeat
            _lowEnergy.value = event.lowEnergy
            _midEnergy.value = event.midEnergy
            _highEnergy.value = event.highEnergy
            _latestEvent.value = event
        }
    }

    /**
     * Inicializa o engine (chamado tipicamente em `onCreate`).
     */
    fun initialize(uri: Uri? = null) {
        engine.initialize(uri)
        startPositionPolling()
    }

    /**
     * Configura uma nova faixa para reprodução.
     */
    fun loadSong(uri: Uri) {
        engine.setupPlayback(uri)
    }

    /**
     * Toca.
     */
    fun play() = engine.play()

    /**
     * Pausa.
     */
    fun pause() = engine.pause()

    /**
     * Libera recursos.
     */
    fun release() {
        positionPollingRunnable?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        positionPollingRunnable = null
        engine.release()
    }

    // --- Polling de posição (rodando no main thread) ---
    private var positionPollingRunnable: Runnable? = null
    private fun startPositionPolling() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        positionPollingRunnable = object : Runnable {
            override fun run() {
                _isPlaying.value = engine.isPlaying()
                _positionMs.value = engine.currentPositionMs()
                _durationMs.value = engine.durationMs()
                handler.postDelayed(this, 100L)
            }
        }
        handler.post(positionPollingRunnable!!)
    }

    companion object {
        fun create(engine: AudioEngineKt): BeatUiController = BeatUiController(engine)
        fun create(context: Context): BeatUiController = BeatUiController(AudioEngineKt(context.applicationContext))
    }
}

/**
 * Helper Compose: observa um StateFlow como State<T>.
 */
@Composable
fun <T> StateFlow<T>.observeAsState(): State<T> = collectAsState()

/**
 * Helper Compose: cria e lembra um BeatUiController para a Composition.
 */
@Composable
fun rememberBeatController(context: Context): BeatUiController {
    return remember(context) { BeatUiController.create(context) }
}
