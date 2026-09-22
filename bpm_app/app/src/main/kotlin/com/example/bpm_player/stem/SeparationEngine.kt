package com.example.bpm_player.stem

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

object SeparationEngine {

    data class SeparationResult(
        val drums: FloatArray,
        val bass: FloatArray,
        val other: FloatArray,
        val vocals: FloatArray,
        val sampleRate: Int
    )

    data class Progress(val percent: Int, val message: String)

    interface ProgressListener {
        fun onProgress(progress: Progress)
    }

    interface InitializationListener {
        fun onInitialized(success: Boolean)
    }

    private const val TAG = "SeparationEngine"
    private const val MODEL_ASSET_PATH = "models/htdemucs.onnx"
    private const val MODEL_FILE_NAME = "htdemucs.onnx"
    private const val TARGET_SAMPLE_RATE = 44100
    private const val FRAME_SIZE = 343980
    private const val HOP_SIZE = 171990

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var modelLoaded = false

    fun initialize(context: Context): Boolean {
        return initialize(context, null)
    }

    fun initialize(context: Context, listener: InitializationListener?): Boolean {
        if (modelLoaded) {
            listener?.onInitialized(true)
            return true
        }

        val modelFile = copyModelFromAssets(context)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.e(TAG, "Model file missing or empty: ${modelFile.absolutePath}")
            listener?.onInitialized(false)
            return false
        }

        return try {
            ortEnv = OrtEnvironment.getEnvironment()

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(4)
                // Desabilita NNAPI por instabilidade em alguns dispositivos (ex: Android 14+)
                // Se precisar de aceleração, use addCpu() explicitamente
                // addNnapi() // Comentado para evitar crash java_class == null
            }

            ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
            modelLoaded = true
            Log.i(TAG, "ONNX model loaded: $modelFile")
            listener?.onInitialized(true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX model: ${e.message}", e)
            // Tenta fallback sem NNAPI se falhou
            if (e.message?.contains("NNAPI") == true || e.message?.contains("java_class") == true) {
                Log.w(TAG, "Retrying without NNAPI...")
                return tryInitializeWithoutNnapi(modelFile, listener)
            }
            listener?.onInitialized(false)
            false
        }
    }

    private fun tryInitializeWithoutNnapi(modelFile: File, listener: InitializationListener? = null): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(4)
                // CPU only, sem NNAPI
            }
            ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
            modelLoaded = true
            Log.i(TAG, "ONNX model loaded (CPU fallback): $modelFile")
            listener?.onInitialized(true)
            true
        } catch (e2: Exception) {
            Log.e(TAG, "Failed to initialize ONNX model (CPU fallback): ${e2.message}", e2)
            listener?.onInitialized(false)
            false
        }
    }

    private fun copyModelFromAssets(context: Context): File {
        val destFile = File(context.filesDir, MODEL_FILE_NAME)
        if (destFile.exists() && destFile.length() > 0) return destFile

        context.assets.open(MODEL_ASSET_PATH).use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } >= 0) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        }
        Log.i(TAG, "Model copied to: ${destFile.absolutePath} (${destFile.length()} bytes)")
        return destFile
    }

    suspend fun separate(
        context: Context,
        samples: FloatArray,
        sampleRate: Int,
        progressListener: ProgressListener? = null
    ): SeparationResult? {
        if (!modelLoaded && !initialize(context)) {
            Log.e(TAG, "Model not loaded, cannot separate")
            return null
        }

        val session = ortSession ?: return null
        val env = ortEnv ?: return null

        return try {
            progressListener?.onProgress(Progress(0, "Preparando áudio..."))

            val resampled = if (sampleRate == TARGET_SAMPLE_RATE) {
                samples
            } else {
                resample(samples, sampleRate, TARGET_SAMPLE_RATE)
            }

            val totalSamples = resampled.size
            if (totalSamples < 2) return null

            val numFrames = max(1, floor((totalSamples - FRAME_SIZE).toDouble() / HOP_SIZE).toInt() + 1)

            val drumsOut = FloatArray(totalSamples)
            val bassOut = FloatArray(totalSamples)
            val otherOut = FloatArray(totalSamples)
            val vocalsOut = FloatArray(totalSamples)

            val windowSum = FloatArray(totalSamples)

            val hannWindow = FloatArray(FRAME_SIZE)
            for (i in hannWindow.indices) {
                hannWindow[i] = 0.5f * (1f - cos(2f * PI.toFloat() * i / (FRAME_SIZE - 1)))
            }

            for (frameIdx in 0 until numFrames) {
                val startSample = frameIdx * HOP_SIZE
                val frameProgress = ((frameIdx + 1).toFloat() / numFrames * 100f).toInt()
                progressListener?.onProgress(
                    Progress(
                        frameProgress.coerceIn(0, 100),
                        "Separando faixas... $frameProgress%"
                    )
                )

                val frame = FloatArray(FRAME_SIZE)
                val copyLen = min(FRAME_SIZE, totalSamples - startSample)
                if (copyLen > 0) {
                    System.arraycopy(resampled, startSample, frame, 0, copyLen)
                }

                for (i in copyLen until FRAME_SIZE) {
                    frame[i] = 0f
                }

                val inputArray = Array(1) { Array(2) { FloatArray(FRAME_SIZE) } }
                for (i in 0 until FRAME_SIZE) {
                    inputArray[0][0][i] = frame[i]
                    inputArray[0][1][i] = frame[i]
                }

                val inputTensor = OnnxTensor.createTensor(env, floatArrayToBuffer(inputArray), longArrayOf(1, 2, FRAME_SIZE.toLong()))

                val inputs = mapOf("mix" to inputTensor)
                val results = session.run(inputs)

                val outputTensor = results["stems"]
                val outputValue: Any = try {
                    outputTensor.javaClass.getMethod("getValue").invoke(outputTensor)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get output tensor value: ${e.message}", e)
                    return@separate null
                }
                val outputArray = outputValue as Array<Array<Array<FloatArray>>>
                // Output shape: [1, 4, 2, 343980] = batch, stems, channels, samples
                val stemsArray = outputArray[0]  // Remove batch dimension -> [4, 2, 343980]

                val stems2D = Array(4) { stemIdx ->
                    val channelArray = stemsArray[stemIdx]  // [2, 343980]
                    val left = channelArray[0]   // [343980]
                    val right = channelArray[1]  // [343980]
                    FloatArray(FRAME_SIZE) { i -> (left[i] + right[i]) * 0.5f }
                }

                for (stemIdx in 0 until 4) {
                    val stemData = stems2D[stemIdx]
                    for (i in 0 until FRAME_SIZE) {
                        val sampleIdx = startSample + i
                        if (sampleIdx < totalSamples) {
                            val windowed = stemData[i] * hannWindow[i]
                            when (stemIdx) {
                                0 -> drumsOut[sampleIdx] += windowed
                                1 -> bassOut[sampleIdx] += windowed
                                2 -> otherOut[sampleIdx] += windowed
                                3 -> vocalsOut[sampleIdx] += windowed
                            }
                            windowSum[sampleIdx] += hannWindow[i]
                        }
                    }
                }

                results.close()
                inputTensor.close()

                if (frameIdx % 10 == 0) {
                    delay(1)
                }
            }

            val eps = 1e-8f
            for (i in 0 until totalSamples) {
                val norm = max(windowSum[i], eps)
                drumsOut[i] /= norm
                bassOut[i] /= norm
                otherOut[i] /= norm
                vocalsOut[i] /= norm
            }

            progressListener?.onProgress(Progress(100, "Concluído!"))

            SeparationResult(
                drums = drumsOut.copyOfRange(0, totalSamples),
                bass = bassOut.copyOfRange(0, totalSamples),
                other = otherOut.copyOfRange(0, totalSamples),
                vocals = vocalsOut.copyOfRange(0, totalSamples),
                sampleRate = TARGET_SAMPLE_RATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Separation failed: ${e.message}", e)
            null
        }
    }

    private fun floatArrayToBuffer(array: Array<Array<FloatArray>>): ByteBuffer {
        val frameSize = array[0][0].size
        val numChannels = array[0].size
        val buffer = ByteBuffer.allocateDirect(frameSize * numChannels * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (i in 0 until frameSize) {
            for (ch in 0 until numChannels) {
                buffer.putFloat(array[0][ch][i])
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (inputRate == outputRate) return input
        val ratio = inputRate.toDouble() / outputRate.toDouble()
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val idx = floor(srcPos).toInt()
            val frac = srcPos - idx
            val nextIdx = min(idx + 1, input.size - 1)
            output[i] = (input[idx] * (1f - frac) + input[nextIdx] * frac).toFloat()
        }
        return output
    }

    fun isInitialized(): Boolean = modelLoaded

    fun release() {
        try {
            ortSession?.close()
        } catch (e: Exception) { }
        ortSession = null
        modelLoaded = false
        Log.i(TAG, "ONNX session released")
    }
}