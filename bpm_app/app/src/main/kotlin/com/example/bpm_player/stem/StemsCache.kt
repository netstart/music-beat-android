package com.example.bpm_player.stem

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

object StemsCache {

    private const val TAG = "StemsCache"
    private const val CACHE_DIR_NAME = "stems"
    private const val MAX_CACHE_ENTRIES = 50

    private fun cacheRoot(context: Context): File {
        return File(context.cacheDir, CACHE_DIR_NAME)
    }

    private fun computeHash(context: Context, uri: Uri): String {
        val key = uri.toString() + "_" + getFileSize(context, uri)
        return md5(key)
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun getCachedStems(context: Context, uri: Uri): List<File>? {
        return withContext(Dispatchers.IO) {
            val hash = computeHash(context, uri)
            val stemDir = File(cacheRoot(context), hash)
            if (!stemDir.exists()) return@withContext null

            val stems = mutableListOf<File>()
            StemTrack.ALL.forEach { track ->
                val stemFile = File(stemDir, track.fileName)
                if (stemFile.exists() && stemFile.length() > 0) {
                    stems.add(stemFile)
                }
            }

            if (stems.size == StemTrack.ALL.size) {
                Log.i(TAG, "Cache hit for uri=$uri, hash=$hash")
                stems
            } else {
                Log.i(TAG, "Cache incomplete for uri=$uri, found ${stems.size}/${StemTrack.ALL.size}")
                stemDir.deleteRecursively()
                null
            }
        }
    }

    suspend fun saveStems(
        context: Context,
        uri: Uri,
        drums: FloatArray,
        bass: FloatArray,
        other: FloatArray,
        vocals: FloatArray,
        sampleRate: Int
    ): List<File> {
        return withContext(Dispatchers.IO) {
            val hash = computeHash(context, uri)
            val stemDir = File(cacheRoot(context), hash)
            if (!stemDir.exists()) {
                stemDir.mkdirs()
            }

            cleanupOldEntries(context)

            val stems = listOf(
                StemTrack.DRUMS to drums,
                StemTrack.BASS to bass,
                StemTrack.OTHER to other,
                StemTrack.VOCALS to vocals
            )

            val savedFiles = mutableListOf<File>()
            stems.forEach { (track, samples) ->
                val file = File(stemDir, track.fileName)
                saveFloatArrayAsWav(samples, sampleRate, file)
                savedFiles.add(file)
            }

            Log.i(TAG, "Saved stems to cache: $stemDir (${savedFiles.size} files)")
            savedFiles
        }
    }

    suspend fun clearCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                cacheRoot(context).deleteRecursively()
                Log.i(TAG, "Stems cache cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear cache: ${e.message}")
            }
        }
    }

    suspend fun getCacheSize(context: Context): Long {
        return withContext(Dispatchers.IO) {
            var totalSize = 0L
            val root = cacheRoot(context)
            if (root.exists()) {
                root.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        totalSize += file.length()
                    }
                }
            }
            totalSize
        }
    }

    private suspend fun cleanupOldEntries(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val root = cacheRoot(context)
                if (!root.exists()) return@withContext

                val entries = root.listFiles()?.sortedBy { it.lastModified() } ?: return@withContext

                var i = 0
                while (i < entries.size && entries.size - i >= MAX_CACHE_ENTRIES) {
                    val oldest = entries[i]
                    oldest.deleteRecursively()
                    Log.d(TAG, "Removed old cache entry: ${oldest.name}")
                    i++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed: ${e.message}")
            }
        }
    }

    private fun saveFloatArrayAsWav(samples: FloatArray, sampleRate: Int, outputFile: File) {
        val numSamples = samples.size
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(stringToBytes("RIFF"))
        buffer.write(intToBytes(totalSize))
        buffer.write(stringToBytes("WAVE"))

        buffer.write(stringToBytes("fmt "))
        buffer.write(intToBytes(16))
        buffer.write(shortToBytes(1))
        buffer.write(shortToBytes(1))
        buffer.write(intToBytes(sampleRate))
        buffer.write(intToBytes(sampleRate * 2))
        buffer.write(shortToBytes(2))
        buffer.write(shortToBytes(16))
        buffer.write(stringToBytes("data"))
        buffer.write(intToBytes(dataSize))

        for (sample in samples) {
            val clamped = max(-1f, min(1f, sample))
            val intSample = (clamped * 32767f).toInt().coerceIn(-32768, 32767)
            buffer.write(intSample and 0xFF)
            buffer.write((intSample shr 8) and 0xFF)
        }

        outputFile.writeBytes(buffer.toByteArray())
    }

    private fun stringToBytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )
    private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
}
