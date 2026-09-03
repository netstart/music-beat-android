package com.example.bpm_player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Captura PCM em tempo real do áudio que está tocando no app.
 * Usa AudioRecord com fonte MIC para capturar o som ambiente.
 * Em ambiente silencioso (ou com fone de ouvido) o PCM reflete a música.
 *
 * Limitação: ruído ambiente pode interferir na análise.
 * Alternativa: capturar via ExoPlayer AudioProcessor (mais complexo).
 */
class AudioAnalyzer(private val context: Context) {

    private val sampleQueue = ConcurrentLinkedQueue<FloatArray>()
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

    @Volatile private var isCapturing = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return
        if (!hasPermission()) {
            Log.w("BPM_AUDIO", "RECORD_AUDIO não concedido — visualizador desabilitado")
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("BPM_AUDIO", "AudioRecord não inicializou")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isCapturing = true

            captureThread = thread(name = "AudioAnalyzer", isDaemon = true) {
                val chunk = FloatArray(2048)
                while (isCapturing) {
                    val read = audioRecord?.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        val copy = FloatArray(read)
                        System.arraycopy(chunk, 0, copy, 0, read)
                        sampleQueue.offer(copy)
                    }
                }
            }
            Log.i("BPM_AUDIO", "Captura iniciada: $sampleRate Hz, MONO, FLOAT")
        } catch (e: Exception) {
            Log.e("BPM_AUDIO", "Falha ao iniciar AudioRecord: ${e.message}")
            stop()
        }
    }

    fun stop() {
        if (!isCapturing) return
        isCapturing = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
        captureThread = null
        sampleQueue.clear()
        Log.i("BPM_AUDIO", "Captura parada")
    }

    /**
     * Consome o frame PCM mais recente (não-bloqueante).
     * Retorna null se não houver nada novo.
     */
    fun consume(): FloatArray? = sampleQueue.poll()

    /**
     * Descarta frames antigos e mantém só o mais recente.
     */
    fun drainLatest(): FloatArray? {
        var latest: FloatArray? = null
        while (true) {
            val next = sampleQueue.poll() ?: break
            latest = next
        }
        return latest
    }

    fun getSampleRate(): Int = sampleRate
}
