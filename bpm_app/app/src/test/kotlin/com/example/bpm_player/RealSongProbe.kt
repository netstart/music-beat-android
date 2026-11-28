package com.example.bpm_player

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Diagnóstico com música real: PCM float32 mono 44,1 kHz em -Dprobe.pcm=... (só roda quando informado). */
class RealSongProbe {
    @Test
    fun probe() {
        val path = System.getProperty("probe.pcm") ?: System.getenv("PROBE_PCM")
        assumeTrue(path != null && File(path).exists())
        val bytes = File(path!!).readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm = FloatArray(fb.remaining()).also { fb.get(it) }
        val sr = 44100
        val win = pcm.copyOfRange(30 * sr, 50 * sr)
        val bpm = BpmDetector.detect(win, sr)
        println("bpm20s=$bpm")
        val s = StructureAnalyzer.analyze(pcm, sr, bpm.bpm) { println(it) }!!
        println("beatMs=${s.beatMs} firstBeatMs=${s.firstBeatMs} downbeat=${s.downbeat} phraseBeat=${s.phraseBeat}")
        for (sec in s.sections) println("%-10s %7.2fs - %7.2fs".format(sec.kind, s.beatStartMs(sec.startBeat) / 1000.0, s.beatStartMs(sec.endBeat) / 1000.0))
        println("primeiros '1': " + (0 until 6).joinToString { "%.3f".format(s.beatStartMs(s.downbeat + it * 8) / 1000.0) })
    }
}
