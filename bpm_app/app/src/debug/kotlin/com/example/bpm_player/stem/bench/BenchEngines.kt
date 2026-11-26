package com.example.bpm_player.stem.bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Descrição de um tensor de entrada/saída (só float32 é suportado pelo bench). */
class TensorSpec(val name: String, val shape: IntArray, val isFloat: Boolean) {
    val count: Int get() = shape.fold(1) { a, b -> a * maxOf(1, b) }
    override fun toString() = "$name${shape.contentToString()}${if (isFloat) "" else " (não-float)"}"
}

/** Motor de inferência medido no benchmark. */
interface BenchEngine : AutoCloseable {
    val label: String
    val inputs: List<TensorSpec>
    val outputs: List<TensorSpec>
    /** Executa uma inferência; devolve as saídas achatadas na mesma ordem de [outputs]. */
    fun run(data: List<FloatArray>): List<FloatArray>
}

// ───────────────────────────── LiteRT (TFLite) ─────────────────────────────

class LiteRtEngine(model: File, useGpu: Boolean, fp16: Boolean, threads: Int, cacheDir: File) : BenchEngine {

    private var gpu: GpuDelegate? = null
    private val interpreter: Interpreter
    override val label: String
    override val inputs: List<TensorSpec>
    override val outputs: List<TensorSpec>
    private val inBufs: List<ByteBuffer>
    private val outBufs: Map<Int, ByteBuffer>

    init {
        val opts = Interpreter.Options().setNumThreads(threads)
        if (useGpu) {
            val compat = CompatibilityList()
            require(compat.isDelegateSupportedOnThisDevice) { "GPU delegate não suportado neste aparelho" }
            val gopts = compat.bestOptionsForThisDevice
                .setPrecisionLossAllowed(fp16)
                .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            // Cache de kernels compilados: 1ª execução "fria", seguintes "quentes".
            cacheDir.mkdirs()
            gopts.setSerializationParams(cacheDir.absolutePath, "htdemucs_${model.length()}_${if (fp16) "f16" else "f32"}")
            gpu = GpuDelegate(gopts)
            opts.addDelegate(gpu)
        }
        interpreter = Interpreter(model, opts)
        label = if (useGpu) "litert_gpu${if (fp16) "_fp16" else "_fp32"}" else "litert_cpu_t$threads"

        inputs = (0 until interpreter.inputTensorCount).map {
            val t = interpreter.getInputTensor(it)
            TensorSpec(t.name(), t.shape(), t.dataType() == DataType.FLOAT32)
        }
        outputs = (0 until interpreter.outputTensorCount).map {
            val t = interpreter.getOutputTensor(it)
            TensorSpec(t.name(), t.shape(), t.dataType() == DataType.FLOAT32)
        }
        inBufs = (0 until interpreter.inputTensorCount).map {
            ByteBuffer.allocateDirect(interpreter.getInputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
        }
        outBufs = (0 until interpreter.outputTensorCount).associateWith {
            ByteBuffer.allocateDirect(interpreter.getOutputTensor(it).numBytes()).order(ByteOrder.nativeOrder())
        }
    }

    override fun run(data: List<FloatArray>): List<FloatArray> {
        inBufs.forEachIndexed { i, b -> b.clear(); b.asFloatBuffer().put(data[i]) }
        outBufs.values.forEach { it.clear() }
        interpreter.runForMultipleInputsOutputs(inBufs.toTypedArray<Any>(), outBufs as Map<Int, Any>)
        return outputs.indices.map { i ->
            val fb = outBufs.getValue(i).also { it.rewind() }.asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
    }
}

// ───────────────────────────── ONNX Runtime ─────────────────────────────

class OrtEngine(model: File, xnnpack: Boolean, threads: Int) : BenchEngine {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    override val label = if (xnnpack) "ort_xnnpack_t$threads" else "ort_cpu_t$threads"
    override val inputs: List<TensorSpec>
    override val outputs: List<TensorSpec>

    init {
        val so = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            // Armadilhas conhecidas: arena + otimizações de grafo levaram a ~5 GB de RAM no Android.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            if (xnnpack) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }
        session = env.createSession(model.absolutePath, so)
        inputs = session.inputInfo.map { (name, node) ->
            val ti = node.info as TensorInfo
            // Eixo de tempo dinâmico (-1) recebe a janela do HTDemucs; demais dinâmicos viram 1 (batch).
            val shape = ti.shape.mapIndexed { i, d -> if (d >= 0) d.toInt() else if (i == ti.shape.lastIndex) StemBenchActivity.SEG else 1 }
            TensorSpec(name, shape.toIntArray(),
                ti.type == ai.onnxruntime.OnnxJavaType.FLOAT)
        }
        outputs = session.outputInfo.map { (name, node) ->
            val ti = node.info as TensorInfo
            TensorSpec(name, ti.shape.map { it.toInt() }.toIntArray(), ti.type == ai.onnxruntime.OnnxJavaType.FLOAT)
        }
    }

    override fun run(data: List<FloatArray>): List<FloatArray> {
        val tensors = inputs.mapIndexed { i, spec ->
            val buf = ByteBuffer.allocateDirect(data[i].size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(data[i]).rewind()
            spec.name to OnnxTensor.createTensor(env, buf, spec.shape.map { it.toLong() }.toLongArray())
        }.toMap()
        try {
            session.run(tensors).use { res ->
                return outputs.map { spec ->
                    val fb: FloatBuffer = (res.get(spec.name).get() as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
    }
}
