package com.example.bpm_player.audio

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Buffer circular lock-free (single producer / single consumer) para PCM mono float.
 *
 * **Por que este design?**
 * - Thread de áudio (producer) nunca bloqueia: se o buffer está cheio, descarta amostras antigas.
 * - Thread FFT (consumer) lê blocos de 1024/2048 sem alocação.
 * - Apenas UMA alocação inicial (capacity em float[capacity]). GC nunca é chamado no hot path.
 * - Sem locks: usa `AtomicLong` com sequence counter para visibilidade entre threads.
 *
 * **Layout**:
 * - `data[capacity]` armazena as amostras em ring buffer.
 * - `writeIndex` (môntono crescente) é a próxima posição de escrita.
 * - `readIndex` é a próxima posição de leitura.
 * - `writeIndex - readIndex` = número de samples disponíveis.
 *
 * **IMPORTANTE**: use power-of-2 capacity para que `index AND (capacity-1)` funcione como módulo.
 */
class RingBufferKt(val capacity: Int) {

    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) {
            "capacity deve ser power of 2 (ex: 1024, 2048, 4096)"
        }
    }

    // Máscara para módulo rápido: index AND mask == index % capacity
    private val mask = capacity - 1

    // Alocação única — NUNCA mexa no array após construção
    private val data = FloatArray(capacity)

    // Índices crescentes (não-wrapam). Use AtomicLong para visibilidade cross-thread.
    private val writeIdx = AtomicLong(0)
    private val readIdx = AtomicLong(0)

    /**
     * Número de samples disponíveis para leitura (sem consumir).
     */
    fun available(): Int {
        val w = writeIdx.get()
        val r = readIdx.get()
        return (w - r).toInt().coerceAtLeast(0)
    }

    /**
     * Capacidade livre para escrita.
     */
    fun freeSpace(): Int = capacity - available()

    /**
     * Escreve samples no buffer. Retorna quantos foram efetivamente escritos.
     * Se o buffer está cheio, descarta samples antigos (overwrite policy).
     *
     * @param src array de origem
     * @param srcOffset offset inicial em src
     * @param length número de samples a escrever
     * @return número de samples escritos (sempre == length, exceto se length > capacity)
     */
    fun put(src: FloatArray, srcOffset: Int, length: Int): Int {
        if (length <= 0) return 0
        val toWrite = min(length, capacity)

        val w = writeIdx.get()
        for (i in 0 until toWrite) {
            data[(w + i).toInt() and mask] = src[srcOffset + i]
        }
        writeIdx.set(w + toWrite)

        // Se o consumer está muito atrás, descarta samples antigos (overwrite)
        val newAvailable = available()
        if (newAvailable > capacity) {
            val toSkip = newAvailable - capacity
            readIdx.addAndGet(toSkip.toLong())
        }

        return toWrite
    }

    /**
     * Lê até `dst.size` samples do buffer. Retorna quantos foram lidos.
     *
     * @param dst buffer de destino (pré-alocado)
     * @param dstOffset offset inicial em dst
     * @param length máximo de samples a ler
     * @return número de samples efetivamente lidos
     */
    fun get(dst: FloatArray, dstOffset: Int, length: Int): Int {
        if (length <= 0) return 0
        val avail = available()
        if (avail == 0) return 0
        val toRead = min(length, avail)

        val r = readIdx.get()
        for (i in 0 until toRead) {
            dst[dstOffset + i] = data[(r + i).toInt() and mask]
        }
        readIdx.addAndGet(toRead.toLong())
        return toRead
    }

    /**
     * Lê N samples sem consumir (peek). Útil para análise sem perder dados.
     */
    fun peek(dst: FloatArray, dstOffset: Int, length: Int): Int {
        val avail = available()
        if (avail == 0) return 0
        val toRead = min(length, avail)
        val r = readIdx.get()
        for (i in 0 until toRead) {
            dst[dstOffset + i] = data[(r + i).toInt() and mask]
        }
        return toRead
    }

    /**
     * Limpa o buffer (descarta tudo que está nele).
     */
    fun clear() {
        val w = writeIdx.get()
        readIdx.set(w)
    }

    /**
     * Posição absoluta de escrita em samples (sempre crescente).
     */
    fun writePosition(): Long = writeIdx.get()

    /**
     * Posição absoluta de leitura em samples (sempre crescente).
     */
    fun readPosition(): Long = readIdx.get()
}
