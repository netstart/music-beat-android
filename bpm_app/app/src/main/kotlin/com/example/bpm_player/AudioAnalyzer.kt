package com.example.bpm_player

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class AudioAnalyzer(private val context: Context) {
    private val frameQueue = ConcurrentLinkedQueue<FloatArray>()
    private val lock = Any()

    @Volatile private var pcm: FloatArray = FloatArray(0)
    @Volatile private var sampleRate: Int = 44100
    @Volatile private var totalDurationUs: Long = 0L
    @Volatile private var decodeInProgress: Boolean = false
    @Volatile private var currentUri: Uri? = null

    private var decodeThread: Thread? = null
    private val windowSamples = 1024

    fun load(uri: Uri) {
        synchronized(lock) {
            if (currentUri == uri && pcm.isNotEmpty()) return
            currentUri = uri
            pcm = FloatArray(0)
            sampleRate = 44100
            totalDurationUs = 0L
            frameQueue.clear()
            decodeInProgress = true
        }
        decodeThread?.interrupt()
        decodeThread = thread(name = "AudioDecoder-File", isDaemon = true) {
            try {
                val pcmData = AudioDecoder.decode(context, uri, maxDurationUs = 0L)
                    ?: run {
                        Log.w("BPM_AUDIO", "Decode vazio para $uri")
                        decodeInProgress = false
                        return@thread
                    }
                synchronized(lock) {
                    if (currentUri != uri) return@thread
                    pcm = pcmData.samples
                    sampleRate = pcmData.sampleRate
                    totalDurationUs = (pcm.size.toLong() * 1_000_000L) / pcmData.sampleRate
                }
                Log.i("BPM_AUDIO", "Decode ok: ${pcm.size} amostras, ${pcmData.sampleRate} Hz")
            } catch (e: Exception) {
                Log.e("BPM_AUDIO", "Falha decode: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                decodeInProgress = false
            }
        }
    }

    fun consume(positionMs: Long, windowMs: Int = 23): FloatArray? {
        val data = pcm
        if (data.isEmpty()) return null
        val sr = sampleRate
        val center = (positionMs * sr / 1000L).toInt()
        val halfWin = (windowMs * sr / 2000).toInt().coerceAtLeast(64)
        val start = (center - halfWin).coerceAtLeast(0)
        val end = (center + halfWin).coerceAtMost(data.size)
        if (end <= start) return null
        val out = FloatArray(end - start)
        System.arraycopy(data, start, out, 0, out.size)
        return out
    }

    fun drainLatest(): FloatArray? = frameQueue.poll()
    fun getSampleRate(): Int = sampleRate
    fun getDurationUs(): Long = totalDurationUs
    fun isReady(): Boolean = pcm.isNotEmpty() && !decodeInProgress

    fun reset() {
        synchronized(lock) {
            pcm = FloatArray(0)
            currentUri = null
            frameQueue.clear()
        }
    }

    fun stop() {
        decodeThread?.interrupt()
        decodeThread = null
        reset()
        decodeInProgress = false
    }
}
