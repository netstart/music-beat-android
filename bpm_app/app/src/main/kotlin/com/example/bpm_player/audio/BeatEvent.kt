package com.example.bpm_player.audio

/**
 * Evento de batida produzido pelo pipeline FFT → consumido pela UI.
 *
 * **Por que data class?**
 * - Imutável (thread-safe entre produtor FFT e consumer Compose).
 * - `equals/hashCode` automático para testes de deduplicação.
 * - Componentes podem desestruturar com `val (bpm, confidence, ...) = event`.
 *
 * @param bpm BPM estimado (60-200) — calibrado pelo detector de batidas.
 * @param confidence 0.0 a 1.0 — quão confiável é a estimativa (baseado no coef. de variação dos intervalos).
 * @param beatPhase Fase no compasso 4/4 (1=a 1ª batida = "tempo 1", 2-4 = batidas secundárias).
 * @param isDownbeat true se esta é a primeira batida do compasso (fase 1).
 * @param timestampMs Posição real no áudio (ms), sincronizado com o playback.
 * @param lowEnergy Energia da banda baixa (kick/bass) — usado para visualizar intensidade.
 * @param midEnergy Energia da banda média (snare/vozes) — usado para visualizar intensidade.
 * @param highEnergy Energia da banda alta (hi-hat) — usado para visualizar intensidade.
 * @param kickEnergy Energia específica do kick — usada para onset detection.
 */
data class BeatEvent(
    val bpm: Float,
    val confidence: Float,
    val beatPhase: Int, // 1..4
    val isDownbeat: Boolean,
    val timestampMs: Long,
    val lowEnergy: Float = 0f,
    val midEnergy: Float = 0f,
    val highEnergy: Float = 0f,
    val kickEnergy: Float = 0f
)
