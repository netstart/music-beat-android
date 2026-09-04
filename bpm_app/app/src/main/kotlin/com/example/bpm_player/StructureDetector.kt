package com.example.bpm_player

/**
 * Detecção de estruturas musicais (seções) via análise de fluxo espectral.
 * Usa variação de energia entre frames consecutivos como indicador de
 * transições entre seções (refrão, verso, ponte, etc.).
 *
 * Nota: sem acesso ao arquivo fonte, esta detecção é probabilística.
 * Ela identifica mudanças abruptas de energia (prováveis transições)
 * e marca as regiões com base na intensidade relativa do sinal.
 */
object StructureDetector {

    enum class Section {
        INTRO,        // Início, energia baixa/constante
        VERSE,        // Verso — energia média, estável
        PRE_CHORUS,   // Pré-refrão — subida de energia
        CHORUS,       // Refrão — energia alta, repetitiva
        BRIDGE,       // Ponte — mudança significativa
        SOLO,         // Solo — variação alta, instrumental
        OUTRO,        // Final — descida de energia
        UNKNOWN       // Não identificado
    }

    data class SectionResult(
        val section: Section,
        val confidence: Float,
        val label: String,
        val description: String
    )

    /**
     * Analisa o fluxo espectral para determinar a seção atual.
     * @param currentEnergy RMS do frame atual
     * @param prevEnergy RMS do frame anterior
     * @param avgEnergy Média móvel dos últimos N frames (para contexto)
     */
    fun detect(currentEnergy: Float, prevEnergy: Float, avgEnergy: Float): SectionResult {
        val energyChange = currentEnergy - prevEnergy
        val relativeEnergy = currentEnergy / avgEnergy.coerceAtLeast(0.001f)
        val changeRatio = if (prevEnergy > 0.001f) currentEnergy / prevEnergy else 1f

        // Fluxo espectral (sudden changes)
        val spectralFlux = kotlin.math.abs(energyChange)

        return when {
            // Refrão: energia muito alta + mudança abrupta
            relativeEnergy > 1.6f && spectralFlux > 0.25f && currentEnergy > avgEnergy * 1.3f ->
                SectionResult(Section.CHORUS, 0.85f, "🎵 Refrão",
                    "Seção repetitiva e de alta energia — provável refrão")

            // Pré-refrão: subida de energia antes do refrão
            relativeEnergy in 1.35f..1.6f && spectralFlux > 0.15f ->
                SectionResult(Section.PRE_CHORUS, 0.65f, "Pré-refrão",
                    "Subida de energia — transição para refrão")

            // Ponte: mudança significativa de padrão (energia muda muito)
            changeRatio > 1.5f && spectralFlux > 0.35f && relativeEnergy > 1.2f ->
                SectionResult(Section.BRIDGE, 0.70f, "🌉 Ponte",
                    "Mudança abrupta — provável ponte ou transição")

            // Solo: alta variação + energia média-alta
            relativeEnergy in 0.8f..1.4f && spectralFlux > 0.2f && currentEnergy > 0.08f ->
                SectionResult(Section.SOLO, 0.55f, "🎸 Solo",
                    "Variação instrumental significativa")

            // Verso: energia estável, média
            relativeEnergy in 0.6f..1.2f && spectralFlux < 0.15f ->
                SectionResult(Section.VERSE, 0.60f, "📖 Verso",
                    "Seção estável — provável verso")

            // Intro: energia baixa, estável no início
            relativeEnergy < 0.7f && currentEnergy < avgEnergy * 0.7f && avgEnergy < 0.05f ->
                SectionResult(Section.INTRO, 0.45f, "🎬 Intro",
                    "Início da música — energia baixa")

            // Outro: energia decaindo no final
            relativeEnergy < 0.5f && currentEnergy < prevEnergy && avgEnergy > 0.05f ->
                SectionResult(Section.OUTRO, 0.50f, "🎬 Final",
                    "Energia decaindo — provável final")

            else ->
                SectionResult(Section.UNKNOWN, 0.30f, "♪",
                    "Seção não identificada — continue ouvindo")
        }
    }
}
