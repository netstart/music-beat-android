# Plano — Separação de Stems On-Device (voz, bateria, baixo, outros)

> Status: **Implementado (v1, motor ORT + XNNPACK)** · GPU (LiteRT) aguarda medição no celular real · Atualizado em 2026-11-26

## 0. O que está no app (v1)

| Peça | Arquivo |
|---|---|
| Decoder estéreo (música inteira, ShortArray pré-alocado) | `stem/StereoDecoder.kt` |
| Separação HTDemucs (janelas 7,8 s, 25% overlap, peso triangular), download do modelo com retomada + SHA-256, cache das 3 últimas músicas | `stem/StemSeparator.kt`, `stem/OverlapAdd.kt` |
| WAV 8 canais → mixer antes do Sonic (solo/mudo/volume em tempo real, BPM preservado) | `stem/StemMixer.kt` |
| Card "Elementos" (estados: sem modelo, baixando, separando, pronto, falha, não suportado) | `activity_main_stemkit.xml`, `item_stem.xml`, `MainActivity.kt` |
| Testes (overlap-add reconstrói o sinal, ganhos solo/mudo, mix com saturação) | `src/test/.../stem/StemsTest.kt` |

**Deixado de fora de propósito (v1):** motor LiteRT GPU (sem medição em celular), serviço em primeiro plano/checkpoint (a separação roda enquanto o app está aberto), faixa de atividade por elemento, FLAC. Telemetria do ONNX Runtime removida do manifesto.
> Tudo roda no aparelho. Nenhum áudio sai do dispositivo.

---

## 1. Decisão técnica (após pesquisa)

| Item | Escolha | Motivo |
|---|---|---|
| Modelo | **HTDemucs v4 base (4 stems)** — drums, bass, other, vocals | Melhor qualidade disponível com export mobile pronto; MIT |
| Motor principal | **LiteRT (TFLite) GPU / OpenCL** — `separator-fused-norm.tflite` (soniqo/HTDemucs-Android, FP32, ~245 MB) | Único número real em celular: S23 Ultra, 60 s de áudio em 20,9 s (RTF 0,35) |
| Motor fallback | **ONNX Runtime CPU (XNNPACK)** — `htdemucs.onnx` (~304 MB FP32; avaliar fp16 de ~90 MB) | Funciona em qualquer arm64; mais lento |
| Evitar | NNAPI, QNN/NPU | Medidos mais lentos que CPU em S23 (outro modelo) e instáveis (crash `java_class == null` na tentativa anterior) |
| Evitar | `htdemucs_ft` (4× mais lento), `htdemucs_6s` (piano com muito vazamento) | Custo/benefício ruim no celular; 6s pode virar modo opcional |
| STFT/iSTFT | Fora do grafo, FFT nativa no host | No GPU export é obrigatório (host-side framing + iSTFT + overlap-add); iSTFT host foi 9,5× mais rápido que dentro do grafo |
| Janela | 7,8 s (343 980 amostras @ 44,1 kHz, estéreo) | Contrato do modelo; variante de 3,9 s para aparelhos ≤ 4 GB RAM |
| Distribuição | **Download sob demanda** + SHA-256 (nunca no APK) | 245–304 MB não cabem no APK base |
| Futuro "modo rápido" | Band-SCNet (2,6 M params) / SCNet Small / Moises-Light | Promissores, sem export Android pronto |
| Alternativa paga | AudioShake Local Inference SDK | On-device, mas por solicitação/preço não público |

### Armadilhas conhecidas (aplicar desde o início)

- ONNX Runtime com defaults chegou a **~5 GB** de RAM no Android → desligar **memory arena** e **graph optimizations** (`ORT_DISABLE_ALL`) — pico fica estável em ~2,2 GB.
- Pico medido no LiteRT GPU: **~1,9 GiB PSS**. Exigir aparelho com ≥ 6 GB RAM para o modo completo; abaixo disso, janela menor ou aviso.
- Primeira execução no GPU compila kernels (27,5 s vs 20,9 s com cache) → aquecer após o download e manter cache do OpenCL.
- `android:largeHeap="true"` não resolve memória nativa; o limite real é o OOM killer → processar em blocos e gravar em disco.

### Erros da tentativa anterior (commit bd1a16d, revertido) que não repetiremos

1. Modelo de 316 MB no APK e no `.gitignore` → nunca chegava ao device.
2. Áudio mono duplicado nos 2 canais → Demucs depende do estéreo.
3. Música inteira em `FloatArray` (5 × ~40 MB) → OOM.
4. Saída convertida para mono.
5. 4 ExoPlayers sincronizados por `Handler` → drift, Sonic separado por player.
6. Coroutine presa à Activity → morre ao girar/apagar a tela.

---

## 2. Arquitetura

```
Arquivo de áudio
 → StereoDecoder (MediaCodec, blocos PCM float 44,1 kHz estéreo, resample se preciso)
 → SeparationEngine (interface)
      ├─ LiteRtGpuEngine   (tflite + OpenCL, STFT/iSTFT no host)
      └─ OrtCpuEngine      (onnx + XNNPACK, arena off)
 → StemWriter (WAV 16-bit multicanal: 2 canais × 4 stems = 8 canais, escrita incremental)
 → StemAnalyzer (RMS por stem e por trecho → elemento presente/ausente + faixa de atividade)
 → ExoPlayer único
      └─ StemMixerAudioProcessor (8ch → 2ch, ganho/mute/solo por stem, lock-free)
      └─ Sonic (time-stretch do BPM, pitch preservado)
```

A separação **não é em tempo real**: roda uma vez por música, vai para o cache e depois toca na hora.

---

## 3. Fases

### Fase 0 — Benchmark no aparelho (go / no-go)
- Harness debug-only (`stem/bench/`) que carrega o modelo de `getExternalFilesDir("models")` (enviado via `adb push`, fora do APK).
- Mede para LiteRT GPU, LiteRT CPU e ORT CPU: tempo de init, tempo por janela (frio e quente), RTF, PSS de pico, temperatura/throttling.
- Disparo por `adb shell am broadcast` (padrão do `TestPlayReceiver`) e resultado no logcat + JSON em `files/bench/`.
- **Saída:** tabela de resultados neste documento e decisão do motor padrão.

### Fase 1 — Decodificação estéreo em blocos
- `StereoDecoder` novo; `AudioDecoder` mono continua para BPM.
- Resample para 44,1 kHz quando necessário.

### Fase 2 — `SeparationEngine`
- Interface comum + `LiteRtGpuEngine` e `OrtCpuEngine`; escolha automática com fallback.
- Framing com reflection padding, STFT/iSTFT nativos (FFT radix-2 4096), overlap-add com transição, normalização da entrada e reversão da escala.
- Validação: soma dos stems ≈ mix (SDR) e comparação com a saída de referência Python.

### Fase 3 — Execução em segundo plano
- Foreground service com notificação (% e cancelar), checkpoint por janela para retomar.

### Fase 4 — Modelos e cache
- `ModelManager`: download sob demanda (com consentimento, tamanho exibido, só Wi-Fi por padrão), SHA-256, versão, apagar modelo.
- `StemsCache`: chave = hash do arquivo + versão do modelo; LRU com teto configurável (4 stems × 4 min ≈ 340 MB em 16-bit; FLAC como otimização futura).

### Fase 5 — Reprodução sincronizada
- WAV 8 canais tocado por **um** ExoPlayer + `StemMixerAudioProcessor` antes do Sonic.
- Sincronia exata, seek normal, slider de BPM preservado.

### Fase 6 — Identificar os elementos
- RMS por stem e por trecho; esconder stems vazios; faixa de atividade por elemento.

### Fase 7 — UI (seguir `DESIGN_SYSTEM.md`)
- Card "Elementos": por stem → ícone, nome, mute, solo, volume. Diferenciação por **ícone**, não cor (acento único `#2EE6A8`).
- Estados: sem modelo, baixando, separando (%), pronto, erro, cancelado, aparelho não suportado.
- Alvos ≥ 48 dp, `contentDescription`, strings PT-BR, `tnum` nos valores de %.

### Fase 8 — Testes
- Unitários: mixer (ganho/solo/mute), STFT↔iSTFT (reconstrução perfeita), overlap-add.
- Instrumentados: fluxo completo com `assets/test/test_music.mp3`.
- Device: bateria e temperatura em música de 4 min.

---

## 4. Resultados da Fase 0

Janela = 7,8 s de áudio. RTF < 1 = mais rápido que tempo real. "3 min" inclui 25% de sobreposição.

| Aparelho | RAM | Motor | Init (s) | Janela fria (s) | Janela quente (s) | RTF | 3 min ≈ | PSS pico | Soma stems vs mix |
|---|---|---|---|---|---|---|---|---|---|
| Emulador x86_64 (API 34, 4 núcleos) | 4 GB | **ORT + XNNPACK** | 2,1 | 8,0 | **7,4** | **0,95** | 227 s | 1,2 GB | **SNR 29,9 dB ✅** |
| Emulador x86_64 | 4 GB | ORT CPU | 2,1 | 15,6 | 11,5 | 1,48 | 354 s | 1,8 GB | SNR 29,9 dB ✅ |
| Emulador x86_64 | 4 GB | LiteRT CPU | 3,8 | 15,2 | 13,1 | 1,67 | 402 s | 1,4 GB | parcial (falta iSTFT) |
| Emulador x86_64 | 4 GB | LiteRT GPU | — | — | — | — | — | — | GPU delegate indisponível no emulador |

**Conclusões (emulador — números de CPU de PC, não de celular):**
- Pipeline validado: `htdemucs.onnx` separa e os 4 stems somados reconstroem a mistura (29,9 dB).
- **XNNPACK é obrigatório no ORT** (≈ 1,6× mais rápido e ~600 MB a menos).
- ORT+XNNPACK vence LiteRT CPU → é o **fallback CPU** definido.
- Arena/otimizações desligadas: pico de 1,2 GB, longe dos ~5 GB relatados — cabe em aparelhos de 4 GB.
- **Pendente:** rodar no celular real (`litert_gpu`, `litert_gpu_fp16`, `ort_xnnpack`) — é o número que decide o motor padrão.

### Como rodar no celular

```powershell
adb install -r bpm_app\app\build\outputs\apk\debug\app-debug.apk
adb shell mkdir -p /sdcard/Android/data/io.github.netstart.beattrack/files/models
adb push separator-fused-norm.tflite /sdcard/Android/data/io.github.netstart.beattrack/files/models/
adb push htdemucs.onnx /sdcard/Android/data/io.github.netstart.beattrack/files/models/
adb shell am start -n io.github.netstart.beattrack/com.example.bpm_player.stem.bench.StemBenchActivity --es engines litert_gpu,litert_gpu_fp16,ort_xnnpack --ei runs 3
adb logcat -s StemBench
```

Modelos: https://huggingface.co/soniqo/HTDemucs-Android (SHA-256 `d2b401f3…7280c0` onnx, `3b4aeed4…a45d445` tflite — conferidos).

---

## 5. Fontes

- soniqo/HTDemucs-Android — https://huggingface.co/soniqo/HTDemucs-Android
- newagedevs, htdemucs no celular — https://newagedevs.com/blog/music-separation-on-a-phone
- demucs-onnx — https://pypi.org/project/demucs-onnx/ · https://stemsplit.github.io/demucs-onnx/models/
- StemSplitio/htdemucs-onnx — https://huggingface.co/StemSplitio/htdemucs-onnx
- Healthykid/htdemucs-onnx (fp16, 90 MB) — https://huggingface.co/Healthykid/htdemucs-onnx
- mixar PR #692 (NNAPI/QNN vs CPU) — https://github.com/geovannimp/mixar/pull/692
- AudioWeb models (janela 3,9 s) — https://huggingface.co/Andremi/audioweb-models
- Band-SCNet — https://www.isca-archive.org/interspeech_2025/yang25d_interspeech.pdf
- Moises-Light — https://arxiv.org/pdf/2510.06785
- AudioShake SDK — https://www.audioshake.ai/sdk
