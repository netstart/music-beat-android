# Plano: estrutura musical (inspirado no emusicality.co.uk)

## 1. Como o emusicality funciona (engenharia reversa, 2026-11-28)

Analisado em `https://emusicality.co.uk/songs/desnudate` (Angular + Tone.js).

**Não há detecção automática.** Cada música é anotada à mão e servida como arquivos estáticos:

| Arquivo | Conteúdo |
|---|---|
| `assets/songs/songs.json` | catálogo: título, artista, gênero, `bpm`, `spotifyId` (11 músicas) |
| `assets/songs/<id>/breakdown.json` | a estrutura (abaixo) |
| `assets/songs/<id>/trackN.trk` | um stem por instrumento, em M4A (7 no exemplo: voz, bongô, güira, baixo, guitarra base, guitarra solo, synth) |

`breakdown.json` do exemplo (resumido):

```json
{
  "trim": 1.85,                 // segundos até o primeiro "1"
  "beatsPerMeasure": 8,         // contagem de dança (1..8)
  "tracks": ["Vocals", {"title":"Bongos","groups":["Percussion","Rhythm"]}, ...],
  "sections": [
    {"title":"Intro", "phrases":[4]},
    {"title":"Chorus", "structure":"Majao", "phrases":[4,4]},
    {"title":"Pre Chorus", "structure":"Derecho", "phrases":[[8,8,8,8,4]]},
    {"title":"Chorus/Outro", "structure":"Breakdown", "phrases":[4, ["Acapella",8,8,8]]}
  ]
}
```

Regras do parser (lidas do `main.js`):

- **Seção** → lista de **frases** → cada frase é uma lista de **medidas** (compassos de 8 tempos).
- Frase como número `N` = N medidas de `beatsPerMeasure` tempos.
- Frase como array = medidas explícitas: número = tempos daquela medida (`4` = meia medida, marcada como "warning" na tela); string troca o rótulo de `structure` (ex.: `"Acapella"`) para as medidas seguintes; objeto `{beats, structure, offset}` também vale.
- Tudo vira um índice de tempo contínuo. Tempo → segundos: `(beat - 1) * 60 / bpm + lag`. O `trim` desloca o início do áudio. **O BPM é fixo na música inteira.**
- A cada tick ele acha a seção, a frase, a medida e o tempo atuais pelo índice.

Interface:

- **Song structure:** barra colorida com as seções em proporção à duração; legenda com uma cor por tipo de seção; tocar numa seção pula até ela.
- **Rhythm / framework:** o rótulo da medida atual (Derecho, Majao, Breakdown, Acapella…), específico de dança.
- **Section phrasing:** uma bolinha por frase da seção; a atual acende.
- **Beats / counts:** 8 bolinhas (1..8), o tempo atual pulsa. Sem pulso quando o gênero é Salsa.
- **Instruments:** mudo por stem, mais o filtro por grupo (Percussion / Rhythm / Guitars / All), que desliga quem não é do grupo.
- **Transporte:** início, frase anterior, play/pause, próxima frase, fim; **loop da frase atual**; fade in/out no play/pause.
- Opcional: tocar pelo Spotify em sincronia (seek com `lag`).

## 2. O que o BeatTrack já tem

| Função | Hoje | Onde |
|---|---|---|
| BPM | detectado (offline + tempo real) | `BpmDetector.kt`, `audio/KissFftBeatDetectorKt.kt` |
| Fase do tempo | `currentPhase()` em tempo real | `KissFftBeatDetectorKt.kt` |
| Stems | 4 (drums, bass, other, vocals) via HTDemucs, com volume/mudo/solo | `stem/StemSeparator.kt`, `stem/StemMixer.kt` |
| Seções | heurística de energia **por frame**, instável, sem grade, e **não é chamada em lugar nenhum** | `StructureDetector.kt` |
| Loop | só da música ou da lista | `MainActivity.kt` |
| Análise | decodifica só 20 s (pula os 30 s iniciais) para o BPM | `AudioDecoder.kt`, `MainActivity.onFilePicked` |
| Tela | layout ativo é `activity_main_stemkit.xml`; posição atualizada a cada 500 ms | `MainActivity.positionUpdater` |

**A diferença principal:** eles têm a estrutura escrita à mão. Nós tocamos qualquer música do aparelho, então a estrutura tem de ser **detectada e depois corrigível pelo usuário**.

## 2.1 Tempo real ou processar antes?

**Processar a música inteira antes (offline), e em tempo real só acompanhar a posição.**

- Seção, frase e "qual é o 1" dependem do que vem **depois**: só dá para saber que algo é refrão quando ele se repete. Em tempo real, a seção só aparece atrasada e muda de ideia, que é o problema do `StructureDetector` atual.
- Offline, a estrutura sai completa: a barra da música inteira aparece de uma vez e dá para pular e fazer loop antes de ouvir.
- O tempo real fica para a **contagem 1–8**, que é só aritmética sobre a grade: `tempo = (posição - offset) / duraçãoDoTempo`. Não precisa de detector rodando.
- Custo: decodificar a música inteira em vez de 20 s. Roda em segundo plano depois do BPM, com o status "Analisando estrutura…". O resultado fica em cache por música, para não refazer.

## 2.2 Algoritmo offline (Kotlin puro, testável como o `BpmDetector`)

Novo `StructureAnalyzer.analyze(pcm, sampleRate, bpm): SongStructure?`:

1. **Reduzir** o PCM para ~11 kHz (média de blocos). Quadros de FFT 1024, hop 256 (~23 ms). Por quadro: fluxo espectral (onsets), fluxo só do grave (< 200 Hz), chroma de 12 notas e RMS.
2. **Grade de tempos:** parte do BPM detectado e testa ±0,6 BPM em passos de 0,02 e todas as fases. Escolhe o par que soma mais onsets sobre a grade na música inteira. Saída: `firstBeatMs` e `beatMs`.
3. **O "1" da contagem de 8:** entre os 8 deslocamentos possíveis, o que tem mais grave nos tempos `≡ r (mod 4)`. Isso dá o 1 do compasso. Para desempatar entre 1 e 5, usa a mudança de harmonia (chroma), que cai mais no 1.
4. **Frases:** blocos de 32 tempos (4 × 8) alinhados no "1". Entre as 4 opções de alinhamento, fica a que concentra mais mudança de chroma e energia nas bordas.
5. **Seções:** um vetor (chroma médio + energia) por bloco de 8 tempos. A novidade compara os 4 blocos antes e depois de cada ponto (distância cosseno). Corta nos picos acima de média + 0,5·desvio, com no mínimo uma frase entre cortes e preferência pela borda de frase.
6. **Rótulos:** agrupa seções parecidas (cosseno do chroma > 0,9 = mesma letra A/B/C). Depois aplica regras simples:
   - grupo repetido de maior energia: **Refrão**;
   - outro grupo repetido: **Verso**;
   - trecho curto logo antes de um refrão: **Pré-refrão**;
   - trecho que não se repete no meio da música: **Ponte**;
   - início e fim de baixa energia: **Intro** e **Final**.

Saída:

```kotlin
data class SongStructure(val beatMs: Double, val firstBeatMs: Double, val downbeat: Int, val sections: List<Section>)
data class Section(val kind: Kind, val startBeat: Int, val endBeat: Int) // fim exclusivo
```

Testes com áudio sintético (como o `BpmDetectorTest`): bateria com o "1" acentuado e blocos de acordes A-B-A-B-C. O teste confere a grade (±20 ms), o "1" e os cortes nas bordas certas.

## 2.3 Tela (dentro do DESIGN_SYSTEM)

Um cartão **Estrutura** entre "Now Playing" e "Elementos", com `bg_card`:

- **Barra de seções** (View própria, 48 dp de altura para o toque): largura proporcional à duração. A seção atual fica em `accent` e as outras em `bg_icon_button`, com divisores `outline` e uma linha de posição. **Não dá para ter uma cor por seção como no emusicality**: o design system exige acento único, então a seção se identifica pelo nome. Tocar numa seção pula até ela.
- **Linha de texto:** nome da seção (`title`) + "Frase 2 de 4" (`meta`, `tnum`).
- **Contagem:** 8 círculos com o número dentro; o atual em `accent`, os outros em `outline`. Atualiza a cada ~40 ms enquanto toca (os 500 ms atuais não acompanham 130 BPM, que tem 460 ms por tempo).
- **Botões de 48 dp:** frase anterior, **loop da frase**, próxima frase. No loop, ao passar do fim da frase dá `seekTo` no início dela; o Sonic mantém o tom com o BPM alterado.
- **Estados:** analisando, sem estrutura (sem batida clara ou BPM não detectado) e pronta.
- **Strings novas** no `strings.xml`, e o DESIGN_SYSTEM ganha a seção 4.13 "Cartão Estrutura" antes de codar.

## 3. O que trazer, em ordem

Cada passo entrega valor sozinho.

1. **Grade de tempos fixa (base de tudo).** Na análise offline que já roda: BPM + **offset do primeiro "1"** (`trim`). Para achar o "1", usar o pico de onsets de grave (stem `bass`/`drums` quando separado; senão filtro passa-baixa) na fase que mais concentra energia a cada 8 tempos. Saída: `beatTimeMs(i) = offset + i * 60000 / bpm`.
2. **Contagem 1–8 na tela.** 8 bolinhas, a atual pulsa (`tnum`, acento `#2EE6A8`). Toque longo no "1" = "o 1 é aqui" (corrige o offset na mão).
3. **Navegação por frase + loop de frase.** Frase = 4 medidas de 8 = 32 tempos. Botões ⏮ frase / frase ⏭ e um botão de loop da frase atual (`ExoPlayer` + `seekTo` no limite; o Sonic mantém o tom).
4. **Seções automáticas.** Trocar o `StructureDetector` por segmentação offline: um vetor (chroma + RMS) por frase, matriz de auto-similaridade, cortes onde a novidade é alta, **sempre na borda de frase**. Rótulo por repetição + energia: o bloco que mais se repete com mais energia é "Refrão"; o inicial de baixa energia, "Intro"; e assim por diante. Barra colorida de seções na tela, com toque para pular.
5. **Edição e salvamento.** Renomear e unir/dividir seções, mover o "1". Salvar por música em JSON **no mesmo formato do `breakdown.json`** (`trim`, `beatsPerMeasure`, `sections[].phrases`), em `filesDir/structure/<hash>.json`. Assim dá para importar e exportar anotações.
6. **Instrumentos por grupo.** Já temos mudo/solo por stem. Falta só um atalho "Ritmo" (drums + bass) e "Melodia" (vocals + other). Mais instrumentos (guitarra separada etc.) exigem outro modelo; fica fora.

Fora do escopo: Spotify (outra licença e SDK, e mexe na política de privacidade); rótulos de dança (Derecho/Majao) automáticos, que só dá para pôr na mão no passo 5; BPM variável dentro da música (eles também não têm).

## 4. Riscos

- **Músicas com BPM que deriva** (gravação ao vivo) desalinham a grade fixa. Mitigação: medir o erro de fase ao longo da música e avisar "grade aproximada".
- **Achar o "1" é o ponto mais frágil.** Por isso o passo 2 já traz a correção manual.
- **Os rótulos automáticos são palpites.** O que é confiável é **onde** a música muda e **o que se repete**; o nome ("Refrão") é estimativa. Por isso a edição do passo 5 importa.
- **Decodificar a música inteira** custa memória (~40 MB de float para 4 min mono a 44 kHz; reduzir para 11 kHz ao decodificar resolve) e tempo, então precisa de cache.
- Tudo é offline e no aparelho: **nada muda na política de privacidade**, a não ser que o passo 5 ganhe compartilhamento.
