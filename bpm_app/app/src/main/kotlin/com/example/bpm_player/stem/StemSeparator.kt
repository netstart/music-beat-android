package com.example.bpm_player.stem

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Estado da separação, sempre ligado à música ([uri]) a que se refere. */
sealed class StemState {
    object Idle : StemState()
    data class Downloading(val uri: Uri, val percent: Int) : StemState()
    data class Separating(val uri: Uri, val percent: Int) : StemState()
    data class Ready(val uri: Uri, val stems: StemsFile) : StemState()
    data class Failed(val uri: Uri, val reason: String) : StemState()
}

/** WAV de 8 canais (drums, bass, other, vocals × L/R) + quais stems estão praticamente em silêncio. */
class StemsFile(val wav: File, val silent: BooleanArray)

/**
 * Separação on-device com HTDemucs (ONNX Runtime + XNNPACK — validado na Fase 0, ver docs/PLANO_STEMS.md).
 * Um job por vez, numa thread própria (não presa à Activity). Nenhum áudio sai do aparelho.
 */
object StemSeparator {

    private const val TAG = "StemSeparator"
    const val SAMPLE_RATE = 44_100
    private const val SEG = 343_980 // janela do modelo: 7,8 s
    private const val STRIDE = SEG * 3 / 4 // 25% de sobreposição, como o Demucs
    private const val CHANNELS = StemMixer.STEMS * 2
    private const val CACHE_KEEP = 3 // shortcut: guarda só as 3 últimas músicas separadas (~85 MB/min cada); virar configuração se pedirem

    const val MODEL_URL = "https://huggingface.co/soniqo/HTDemucs-Android/resolve/main/htdemucs.onnx"
    private const val MODEL_SHA256 = "d2b401f322558cd57d67a752ed7be3fa55178a0626011eda8ac7bb74e17280c0"
    const val MODEL_BYTES = 304_321_552L

    private val _state = MutableStateFlow<StemState>(StemState.Idle)
    val state: StateFlow<StemState> = _state

    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false

    /** ONNX Runtime exige API 24 e o pico de ~1,2 GB precisa de processo 64-bit e RAM folgada. */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24 || !Process.is64Bit()) return false
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem >= 3L * 1024 * 1024 * 1024
    }

    fun modelFile(context: Context) = File(context.filesDir, "models/htdemucs.onnx")

    // shortcut: confere só o tamanho do modelo já baixado (SHA-256 é verificado no download); rehash se surgir corrupção
    fun isModelReady(context: Context) = modelFile(context).length() == MODEL_BYTES

    /** Stems já separados desta música, se existirem no cache. */
    fun cached(context: Context, uri: Uri): StemsFile? {
        val dir = cacheDir(context, uri) ?: return null
        val wav = File(dir, "stems.wav")
        val meta = File(dir, "silent.txt")
        if (!wav.exists() || !meta.exists()) return null
        dir.setLastModified(System.currentTimeMillis())
        val silent = meta.readText().trim().split(',').map { it == "1" }.toBooleanArray()
        if (silent.size != StemMixer.STEMS) return null
        return StemsFile(wav, silent)
    }

    @Synchronized
    fun start(context: Context, uri: Uri) {
        if (worker?.isAlive == true) return
        val app = context.applicationContext
        cancelled = false
        worker = Thread({ run(app, uri) }, "StemSeparator").apply { start() }
    }

    fun cancel() {
        cancelled = true
    }

    /** Volta ao repouso quando a música muda e não há job em andamento para ela. */
    fun reset() {
        if (worker?.isAlive != true) _state.value = StemState.Idle
    }

    private fun run(context: Context, uri: Uri) {
        try {
            cached(context, uri)?.let { _state.value = StemState.Ready(uri, it); return }
            if (!isModelReady(context)) download(context, uri)
            _state.value = StemState.Separating(uri, 0)
            val stems = separate(context, uri)
            _state.value = StemState.Ready(uri, stems)
        } catch (e: InterruptedException) {
            _state.value = StemState.Idle
        } catch (t: Throwable) {
            Log.e(TAG, "falha na separação", t)
            _state.value = StemState.Failed(uri, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun checkCancel() {
        if (cancelled) throw InterruptedException()
    }

    /** 300 MB em rede móvel caem no meio: retoma do .part (HTTP Range) e tenta algumas vezes. */
    private fun download(context: Context, uri: Uri) {
        val target = modelFile(context).also { it.parentFile?.mkdirs() }
        val part = File(target.path + ".part")
        for (attempt in 1..5) {
            try {
                downloadOnce(part, uri)
                break
            } catch (e: java.io.IOException) {
                if (attempt == 5) throw e
                Log.w(TAG, "download interrompido (tentativa $attempt), retomando: $e")
                Thread.sleep(2_000L * attempt)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = input.read(buf); if (r < 0) break; digest.update(buf, 0, r) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != MODEL_SHA256) {
            part.delete()
            throw IllegalStateException("modelo corrompido (SHA-256 não confere)")
        }
        check(part.renameTo(target)) { "não foi possível salvar o modelo" }
    }

    private fun downloadOnce(part: File, uri: Uri) {
        var done = part.length()
        if (done >= MODEL_BYTES) return
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
            val append = when (conn.responseCode) {
                206 -> true
                200 -> { done = 0; false } // servidor ignorou o Range: recomeça
                else -> throw IllegalStateException("download do modelo falhou (HTTP ${conn.responseCode})")
            }
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        checkCancel()
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = (done * 100 / MODEL_BYTES).toInt().coerceAtMost(100)
                        if (pct != lastPct) { lastPct = pct; _state.value = StemState.Downloading(uri, pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun separate(context: Context, uri: Uri): StemsFile {
        val pcm = StereoDecoder.decode(context, uri) { cancelled }
        val total = pcm.framesAt(SAMPLE_RATE)
        val windows = (total + STRIDE - 1) / STRIDE

        val dir = cacheDir(context, uri) ?: throw IllegalStateException("música sem identificador")
        dir.mkdirs()
        val tmp = File(dir, "stems.wav.part")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(getIntraThreads())
            // Arena e otimizações de grafo desligadas: com elas o ORT chega a ~5 GB no Android (Fase 0: pico 1,2 GB assim).
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            addXnnpack(mapOf("intra_op_num_threads" to getIntraThreads().toString()))
        }
        val sumSq = DoubleArray(StemMixer.STEMS)
        try {
            env.createSession(modelFile(context).absolutePath, opts).use { session ->
                val inputName = session.inputNames.first()
                val inBuf = ByteBuffer.allocateDirect(2 * SEG * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val pred = FloatArray(CHANNELS * SEG)
                val ola = OverlapAdd(CHANNELS, SEG, STRIDE)
                WavWriter(tmp, CHANNELS, SAMPLE_RATE).use { wav ->
                    for (k in 0 until windows) {
                        checkCancel()
                        val offset = k * STRIDE
                        inBuf.clear()
                        for (c in 0 until 2) for (t in 0 until SEG) {
                            val i = offset + t
                            inBuf.put(if (i < total) pcm.sampleAt(c, i, SAMPLE_RATE) else 0f)
                        }
                        inBuf.rewind()
                        OnnxTensor.createTensor(env, inBuf, longArrayOf(1, 2, SEG.toLong())).use { input ->
                            session.run(mapOf(inputName to input)).use { res ->
                                (res.get(0) as OnnxTensor).floatBuffer.get(pred) // [1,4,2,SEG] = canal-maior com 8 canais
                            }
                        }
                        val count = minOf(STRIDE, total - offset)
                        ola.push(pred, count) { frames, n ->
                            for (t in 0 until n) for (s in 0 until StemMixer.STEMS) {
                                val l = frames[t * CHANNELS + s * 2]
                                val r = frames[t * CHANNELS + s * 2 + 1]
                                sumSq[s] += (l * l + r * r).toDouble()
                            }
                            wav.write(frames, n)
                        }
                        _state.value = StemState.Separating(uri, (k + 1) * 100 / windows)
                    }
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        } finally {
            opts.close()
        }

        val rms = sumSq.map { sqrt(it / (2.0 * total)) }
        val loudest = rms.maxOrNull() ?: 0.0
        // Stem < 3% do mais alto (~−30 dB) é tratado como ausente (ex.: sem voz numa instrumental).
        val silent = BooleanArray(StemMixer.STEMS) { rms[it] < loudest * 0.03 }
        val wav = File(dir, "stems.wav")
        check(tmp.renameTo(wav)) { "não foi possível salvar os stems" }
        File(dir, "silent.txt").writeText(silent.joinToString(",") { if (it) "1" else "0" })
        trimCache(context)
        return StemsFile(wav, silent)
    }

    private fun getIntraThreads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun stemsRoot(context: Context) = File(context.cacheDir, "stems")

    /** Chave = URI + tamanho do arquivo (muda se o arquivo for trocado). */
    private fun cacheDir(context: Context, uri: Uri): File? {
        val size = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
        val key = MessageDigest.getInstance("SHA-1").digest("$uri|$size".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(stemsRoot(context), key)
    }

    private fun trimCache(context: Context) {
        stemsRoot(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(CACHE_KEEP)?.forEach { it.deleteRecursively() }
    }
}

/** WAV PCM 16-bit intercalado; os tamanhos do cabeçalho são corrigidos no [close]. */
class WavWriter(private val file: File, private val channels: Int, private val rate: Int) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private val scratch = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    init {
        out.write(header(0))
    }

    fun write(frames: FloatArray, count: Int) {
        val n = count * channels
        var i = 0
        while (i < n) {
            scratch.clear()
            while (i < n && scratch.remaining() >= 2) {
                scratch.putShort((frames[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                i++
            }
            out.write(scratch.array(), 0, scratch.position())
            dataBytes += scratch.position()
        }
    }

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(dataBytes)) }
    }

    private fun header(data: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + data).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
        putInt(rate); putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
        put("data".toByteArray()); putInt(data.toInt())
    }.array()
}
