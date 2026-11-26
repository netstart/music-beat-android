package com.example.bpm_player.stem.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bpm_player.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Fase 0 — benchmark de separação de stems (HTDemucs) on-device. Somente build debug.
 *
 * Modelos ficam FORA do APK, em /sdcard/Android/data/com.example.bpm_player/files/models/
 *
 *   adb shell am start -n com.example.bpm_player/.stem.bench.StemBenchActivity \
 *       --es engines litert_gpu,litert_gpu_fp16,litert_cpu,ort_xnnpack,ort_cpu --ei runs 3
 *
 * Saída: tela, logcat (tag StemBench) e JSON em files/bench/.
 */
class StemBenchActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private val sb = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad, pad, pad, pad)
            text = getString(R.string.bench_starting)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg_base))
            addView(out)
        })

        val engines = (intent.getStringExtra("engines") ?: "litert_gpu,litert_cpu,ort_xnnpack")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val cfg = Config(
            engines = engines,
            tflite = intent.getStringExtra("tflite") ?: "separator-fused-norm.tflite",
            onnx = intent.getStringExtra("onnx") ?: "htdemucs.onnx",
            runs = intent.getIntExtra("runs", 3).coerceIn(1, 20),
            threads = intent.getIntExtra("threads", 4).coerceIn(1, 16),
        )
        Thread({ runBench(cfg) }, "StemBench").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    private data class Config(val engines: List<String>, val tflite: String, val onnx: String, val runs: Int, val threads: Int)

    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(sb) { sb.append(line).append('\n') }
        runOnUiThread { out.text = synchronized(sb) { sb.toString() } }
    }

    private fun runBench(cfg: Config) {
        val modelsDir = getExternalFilesDir("models")!!.also { it.mkdirs() }
        val report = JSONObject()
        report.put("device", deviceInfo())
        log("== StemBench ==")
        log(report.getJSONObject("device").toString(2))
        log("modelos em: ${modelsDir.absolutePath}")

        val audio = try {
            BenchAudio.decodeAssetStereo(this, "test/test_music.mp3", 0, 60_000_000)
        } catch (e: Exception) {
            log("ERRO ao decodificar áudio de teste: $e"); return
        }
        log(String.format(Locale.US, "áudio: %.1f s estéreo @ 44,1 kHz", audio.frames / 44_100.0))

        val results = JSONArray()
        for (name in cfg.engines) {
            val r = JSONObject().put("engine", name)
            try {
                benchOne(name, cfg, modelsDir, audio, r)
            } catch (t: Throwable) {
                r.put("error", t.toString())
                log("[$name] ERRO: $t")
                Log.e(TAG, "falha em $name", t)
            }
            results.put(r)
            System.gc(); SystemClock.sleep(1500)
        }
        report.put("results", results)

        val dir = getExternalFilesDir("bench")!!.also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "stembench_$stamp.json").apply { writeText(report.toString(2)) }
        log("\nJSON: ${f.absolutePath}")
        log("== FIM ==")
    }

    private fun benchOne(name: String, cfg: Config, modelsDir: File, audio: BenchAudio.Stereo, r: JSONObject) {
        val isTflite = name.startsWith("litert")
        val model = File(modelsDir, if (isTflite) cfg.tflite else cfg.onnx)
        if (!model.exists()) {
            throw IllegalStateException("modelo ausente: ${model.name} — envie com: adb push ${model.name} ${modelsDir.absolutePath}/")
        }
        log("\n[$name] modelo ${model.name} (${model.length() / (1024 * 1024)} MB)")

        val mem = PssSampler().also { it.start() }
        try {
            benchEngine(name, cfg, model, audio, r, mem)
        } finally {
            mem.stopSampling()
        }
    }

    private fun benchEngine(name: String, cfg: Config, model: File, audio: BenchAudio.Stereo, r: JSONObject, mem: PssSampler) {
        val t0 = SystemClock.elapsedRealtime()
        val engine: BenchEngine = when (name) {
            "litert_gpu" -> LiteRtEngine(model, useGpu = true, fp16 = false, threads = cfg.threads, cacheDir = File(cacheDir, "gpu_cache"))
            "litert_gpu_fp16" -> LiteRtEngine(model, useGpu = true, fp16 = true, threads = cfg.threads, cacheDir = File(cacheDir, "gpu_cache"))
            "litert_cpu" -> LiteRtEngine(model, useGpu = false, fp16 = false, threads = cfg.threads, cacheDir = cacheDir)
            "ort_cpu" -> OrtEngine(model, xnnpack = false, threads = cfg.threads)
            "ort_xnnpack" -> OrtEngine(model, xnnpack = true, threads = cfg.threads)
            else -> throw IllegalArgumentException("motor desconhecido: $name")
        }
        engine.use { e ->
            val initMs = SystemClock.elapsedRealtime() - t0
            r.put("label", e.label).put("init_ms", initMs)
            log("[$name] init ${initMs} ms")
            log("[$name] entradas: ${e.inputs.joinToString()}")
            log("[$name] saídas:   ${e.outputs.joinToString()}")
            require(e.inputs.all { it.isFloat }) { "entrada não-float não suportada pelo bench" }

            val times = JSONArray()
            val windows = (audio.frames / SEG).coerceAtLeast(1)
            for (i in 0..cfg.runs) { // i == 0 é a execução fria
                val start = (i % windows) * SEG
                val feed = InputBuilder(audio, start)
                val data = e.inputs.map { feed.fill(it) }
                val s = SystemClock.elapsedRealtime()
                val outs = e.run(data)
                val ms = SystemClock.elapsedRealtime() - s
                times.put(ms)
                val thermal = thermalStatus()
                log(String.format(Locale.US, "[%s] %s %d ms  (RTF %.3f)  PSS pico %d MB  térmico %s",
                    name, if (i == 0) "fria  " else "quente", ms, ms / (SEG * 1000.0 / 44_100), mem.peakMb(), thermal))
                if (i == 0) sanity(name, e, feed, outs, r)
            }
            val warm = (1 until times.length()).map { times.getLong(it) }
            val avg = warm.average()
            r.put("cold_ms", times.getLong(0)).put("warm_ms", times).put("warm_avg_ms", avg)
                .put("rtf_warm", avg / (SEG * 1000.0 / 44_100)).put("pss_peak_mb", mem.peakMb())
                .put("thermal_end", thermalStatus())
            log(String.format(Locale.US, "[%s] RESUMO quente %.0f ms/janela · RTF %.3f · música de 3 min ≈ %.0f s · PSS pico %d MB",
                name, avg, avg / (SEG * 1000.0 / 44_100), avg / (SEG * 1000.0 / 44_100) * 180 * HOP_FACTOR, mem.peakMb()))
        }
    }

    /** Checagem rápida: num modelo de saída [1,4,2,SEG], a soma dos stems deve reconstruir a mistura. */
    private fun sanity(name: String, e: BenchEngine, feed: InputBuilder, outs: List<FloatArray>, r: JSONObject) {
        val idx = outs.indexOfFirst { it.size == 4 * 2 * SEG } // forma real; a declarada pode ser dinâmica
        if (idx < 0) {
            log("[$name] sanidade: nenhuma saída [4,2,$SEG] — pulado")
            return
        }
        val y = outs[idx]
        val partial = e.outputs.size > 1
        var sig = 0.0; var err = 0.0
        val rms = DoubleArray(4)
        for (c in 0 until 2) for (t in 0 until SEG) {
            val x = feed.norm(c, t).toDouble()
            var sum = 0.0
            for (s in 0 until 4) {
                val v = y[(s * 2 + c) * SEG + t].toDouble()
                sum += v; rms[s] += v * v
            }
            sig += x * x; err += (x - sum) * (x - sum)
        }
        val snr = 10 * log10(sig / maxOf(err, 1e-12))
        val stems = listOf("drums", "bass", "other", "vocals")
        val rmsTxt = stems.indices.joinToString { String.format(Locale.US, "%s %.3f", stems[it], sqrt(rms[it] / (2 * SEG))) }
        log(String.format(Locale.US, "[%s] sanidade: soma dos stems vs mistura SNR %.1f dB%s · RMS %s",
            name, snr, if (partial) " (PARCIAL: falta o ramo espectral/iSTFT no host)" else "", rmsTxt))
        r.put("mix_recon_snr_db", snr).put("mix_recon_partial", partial)
    }

    /** Monta as entradas a partir de uma janela de áudio real, normalizada como no Demucs. */
    private class InputBuilder(private val a: BenchAudio.Stereo, private val start: Int) {
        private val mean: Float
        private val std: Float

        init {
            var m = 0.0; var q = 0.0
            for (t in 0 until SEG) { val v = (raw(0, t) + raw(1, t)) / 2.0; m += v; q += v * v }
            m /= SEG
            mean = m.toFloat()
            std = maxOf(sqrt(q / SEG - m * m), 1e-5).toFloat()
        }

        private fun raw(c: Int, t: Int): Float {
            val i = start + t
            if (i < 0 || i >= a.frames) return 0f
            return if (c == 0) a.left[i] else a.right[i]
        }

        fun norm(c: Int, t: Int): Float = (raw(c, t) - mean) / std

        /** Reflete índices fora da janela, como o padding do STFT. */
        private fun reflect(c: Int, t: Int): Float {
            var k = t
            if (k < 0) k = -k
            if (k >= SEG) k = 2 * (SEG - 1) - k
            return norm(c, k.coerceIn(0, SEG - 1))
        }

        fun fill(spec: TensorSpec): FloatArray {
            val s = spec.shape
            val n = spec.count
            val out = FloatArray(n)
            when {
                s.size >= 2 && s.last() == SEG && s[s.size - 2] == 2 -> // [.., 2, SEG]: forma de onda
                    for (c in 0 until 2) for (t in 0 until SEG) out[c * SEG + t] = norm(c, t)
                s.size == 2 && s[0] % 2 == 0 -> { // [2*frames, nfft]: quadros com reflection padding
                    val frames = s[0] / 2
                    val nfft = s[1]
                    val hop = nfft / 4
                    for (row in 0 until s[0]) {
                        val c = row / frames
                        val f = row % frames
                        val base = f * hop - nfft / 2
                        for (k in 0 until nfft) out[row * nfft + k] = reflect(c, base + k)
                    }
                }
                else -> for (i in 0 until n) out[i] = norm(i % 2, (i / 2) % SEG)
            }
            return out
        }
    }

    /** Amostra o PSS do processo para registrar o pico durante a inferência. */
    private class PssSampler : Thread("PssSampler") {
        @Volatile private var running = true
        @Volatile private var peakKb = 0L
        override fun run() {
            while (running) {
                peakKb = maxOf(peakKb, Debug.getPss())
                try { sleep(250) } catch (_: InterruptedException) { return }
            }
        }
        fun peakMb(): Long = maxOf(peakKb, Debug.getPss()) / 1024
        fun stopSampling() { running = false; interrupt() }
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "n/d"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val st = pm.currentThermalStatus
        val head = if (Build.VERSION.SDK_INT >= 30) String.format(Locale.US, " headroom %.2f", pm.getThermalHeadroom(0)) else ""
        return "$st$head"
    }

    private fun deviceInfo(): JSONObject {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.joinToString())
            .put("ram_mb", mi.totalMem / (1024 * 1024))
            .put("cores", Runtime.getRuntime().availableProcessors())
    }

    companion object {
        private const val TAG = "StemBench"
        /** Janela do HTDemucs: 7,8 s a 44,1 kHz. */
        const val SEG = 343_980
        /** Sobreposição de 25% entre janelas no pipeline real → ~1,33 janelas por segmento útil. */
        private const val HOP_FACTOR = 1.0 / 0.75
    }
}
