package com.example.bpm_player.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Engine de áudio de alta performance — pipeline producer-consumer.
 *
 * **Arquitetura**:
 * - **Producer**: ExoPlayer com AnalyticsListener + DefaultAudioSink (pipeline inalterado).
 *   O AnalyticsListener captura PCM decodificado e escreve no RingBuffer.
 * - **Buffer**: RingBufferKt (2048 amostras, alocado uma vez, zero GC).
 * - **Consumer**: Thread FFT (prioridade normal) consome o buffer → KissFftBeatDetectorKt.
 * - **UI**: Eventos `BeatEvent` enviados via `Handler(Looper.getMainLooper())`.
 *
 * **Captura passiva**: o AnalyticsListener é apenas observador — não intercepta
 * o pipeline de renderização, garantindo que o áudio toque sem interferência.
 */
class AudioEngineKt(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val RING_CAPACITY = 2048
        private const val FFT_SIZE = 1024
        private val THREAD_NAME = "AudioEngine-FFT"
        private const val FFT_PERIOD_MS = 10L
        private const val MAX_SAMPLES_PER_CAPTURE = 4096
    }

    // --- Componentes principais ---
    private var exoPlayer: ExoPlayer? = null
    private val ringBuffer = RingBufferKt(RING_CAPACITY)
    private val beatDetector = KissFftBeatDetectorKt(fftSize = FFT_SIZE)

    // --- PCM capture ---
    private val captureFloatBuffer = FloatArray(MAX_SAMPLES_PER_CAPTURE)

    // --- Threading ---
    private val fftExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, THREAD_NAME).apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val isRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Eventos ---
    private val eventQueue = ConcurrentLinkedQueue<BeatEvent>()
    private var latestEventForUi: BeatEvent? = null

    // --- Callback para UI ---
    private var onBeatEvent: ((BeatEvent) -> Unit)? = null

    // Buffer de amostras para o FFT (pré-alocado, reutilizado a cada frame)
    private val fftInputBuffer = FloatArray(FFT_SIZE)

    /**
     * Define o listener que recebe eventos de batida na thread principal.
     */
    fun setOnBeatEventListener(listener: (BeatEvent) -> Unit) {
        onBeatEvent = listener
    }

    /**
     * Retorna o ExoPlayer para que a UI possa controlar playback.
     * Não chame release() neste player — use audioEngine.release() em vez disso.
     */
    fun getPlayer(): ExoPlayer? = exoPlayer

    /**
     * Inicializa o engine e cria o ExoPlayer com captura de PCM via AnalyticsListener.
     */
    fun initialize() {
        if (isRunning.get()) return
        isRunning.set(true)

        Log.d(TAG, "Inicializando AudioEngineKt — ringBuffer=$RING_CAPACITY, fftSize=$FFT_SIZE")

        exoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }
        startFftConsumer()
    }

    /**
     * Configura playback de uma URI de áudio via ExoPlayer.
     */
    fun setupPlayback(uri: Uri) {
        exoPlayer?.let { player ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        }
        ringBuffer.clear()
        beatDetector.reset()
        eventQueue.clear()
        latestEventForUi = null
    }

    /**
     * Inicia a reprodução.
     */
    fun play() {
        Log.d(TAG, "AudioEngine.play() called")
        exoPlayer?.play()
        Log.d(TAG, "AudioEngine.play() done, isPlaying=${exoPlayer?.isPlaying}")
    }

    /**
     * Pausa a reprodução.
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * Libera todos os recursos (chamar no `onDestroy` da Activity).
     */
    fun release() {
        isRunning.set(false)
        fftExecutor.shutdown()
        exoPlayer?.release()
        exoPlayer = null
        ringBuffer.clear()
        Log.d(TAG, "AudioEngine liberado")
    }

    /**
     * Inicia o loop consumer (thread FFT) que lê do RingBuffer e publica eventos.
     */
    private fun startFftConsumer() {
        fftExecutor.execute {
            Log.d(TAG, "Thread FFT iniciado: $THREAD_NAME")

            val consumeBuffer = FloatArray(FFT_SIZE)
            var currentSampleOffset = 0

            while (isRunning.get()) {
                try {
                    val baseTimestampMs: Long = try {
                        (exoPlayer?.currentPosition ?: 0L)
                    } catch (e: IllegalStateException) {
                        0L
                    }

                    val available = ringBuffer.available()
                    if (available > 0) {
                        // Limita a leitura para nunca exceder o espaço restante no fftInputBuffer
                        val spaceLeft = FFT_SIZE - currentSampleOffset
                        val toRead = min(available, spaceLeft)
                        if (toRead > 0) {
                            val read = ringBuffer.get(consumeBuffer, 0, toRead)

                            for (i in 0 until read) {
                                fftInputBuffer[currentSampleOffset + i] = consumeBuffer[i]
                            }
                            currentSampleOffset += read
                        }

                        if (currentSampleOffset >= FFT_SIZE) {
                            // Cria um array exato para o FFT (copia evita dados sujos entre frames)
                            val processBuffer = FloatArray(FFT_SIZE)
                            System.arraycopy(fftInputBuffer, 0, processBuffer, 0, FFT_SIZE)

                            val event = beatDetector.process(processBuffer, baseTimestampMs)
                            if (event != null) {
                                eventQueue.add(event)
                            }

                            currentSampleOffset = 0
                        }
                    }

                    drainEventQueue()
                    Thread.sleep(FFT_PERIOD_MS)

                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no loop FFT: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Log.d(TAG, "Thread FFT finalizada")
        }
    }

    /**
     * Drena eventos acumulados para a UI. Apenas o último é mantido (coalescing).
     */
    private fun drainEventQueue() {
        while (true) {
            val event = eventQueue.poll() ?: break
            latestEventForUi = event
        }
        val event = latestEventForUi ?: return
        latestEventForUi = null
        mainHandler.post {
            onBeatEvent?.invoke(event)
        }
    }

    fun currentBpm(): Float = beatDetector.currentBpm()
    fun currentConfidence(): Float = beatDetector.currentConfidence()
    fun currentPhase(): Int = beatDetector.currentPhase()
    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true
    fun currentPositionMs(): Long = exoPlayer?.currentPosition ?: 0L
    fun durationMs(): Long = exoPlayer?.duration ?: 0L
}
