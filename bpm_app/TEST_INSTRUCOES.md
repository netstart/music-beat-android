# Migration Performance Test — music-beat-android

## 1. Teste em Dispositivo Real (API 21+)

```bash
# Build e instalar via USB (dispositivo real obrigatório para latência <5ms)
./gradlew :app:assembleDebug
adb install -r bpm_app/app/build/outputs/apk/debug/app-debug.apk
```

- **Dispositivo recomendado**: Pixel 6/7, Samsung S23, qualquer dispositivo com SoC de áudio (Qualcomm Snapdragon 7xx+ / MediaTek Dimensity 9000+).
- **Não usar emulador** para validação de latência — o emulador não simula o hardware decode do MediaCodec.

---

## 2. Android Studio Profiler — Validação de Performance

### CPU (thread de áudio)
```bash
# Capturar profiling
./gradlew :app:profileDebug
```

- Abra **Profiler → CPU** e filtre pela thread do ExoPlayer (`AudioTrackThread` / `ExoPlayer-...`).
- **Meta**: CPU < 5% no thread de áudio (MediaCodec faz decode em DSP/NEON; o thread Kotlin deve apenas copiar PCM).
- **Se >5%**: verifique se o `RingBuffer.put()` está copiando no thread de áudio (não deve). Se houver cópia extra, o buffer não está sendo usado corretamente.

### Memória / GC
- **Profiler → Memory → Garbage Collection**: deve mostrar **0 GC events** durante 30 segundos de reprodução contínua.
- **Se houver GC**: o `RingBufferKt` ou o `PcmCaptureProcessor` estão alocando arrays no hot path. Verifique se todos os `FloatArray` são pré-alocados.

### Latência (método indireto — Android Studio não mede diretamente)
- Grave uma faixa de 120 BPM com batidas claras.
- No app, observe se o gráfico (Canvas) pulsa **exatamente** no tempo 1 quando a batida ocorre.
- **Se houver atraso > 5ms**: reduza `FFT_PERIOD_MS` (de 10ms para 5ms) — aumenta o consumo de CPU, mas reduz latência.
- **Alternativa precisa**: use `adb shell dumpsys media.audio` ou um app externo como *Audio Range* para medir delay de reprodução.

---

## 3. Verificação de Pipeline

| Etapa | Como verificar | Resultado esperado |
|---|---|---|
| Decode hardware | `adb logcat | grep -i "MediaCodec\|MediaPlayer"` | Mensagens de `MediaCodec` criando decoder para `audio/mp3` ou `audio/mp4a-latm` |
| RingBuffer | `Log.d("RingBuffer")` (adicione log no `put/get`) | Nenhum `OutOfMemory`; `available()` cresce e decresce suavemente |
| FFT | `Log.d("AudioEngine")` (já existe no `startFftConsumer`) | Mensagens a cada 10ms (`FFT_PERIOD_MS`) |
| BeatEvent | `Log.d("AudioEngine")` no listener | Eventos a cada ~500ms (120 BPM = 2 batidas/seg, mas FFT roda a cada 10ms) |
| Timestamp sincronizado | `currentPositionFormatted()` vs tempo observado | `0:00` → `0:01` ... segue o playback real |

---

## 4. Ajustes Comuns

### Se o gráfico não sincroniza com a música
- Verifique se `audioEngine.setupPlayback(uri)` está chamando `player.prepare()` — sem isso, o `ExoPlayer` não inicia o decode.
- Verifique se `beatController.setOnBeatEventListener` está conectado — sem listener, eventos são descartados.

### Se o BPM não é detectado
- Verifique se o arquivo de áudio não é silencioso (RMS < 0.01 = ignorado pelo `BpmDetector`).
- Verifique se a faixa tem batidas claras (música eletrônica, pop, rock funcionam melhor).
- O `KissFftBeatDetectorKt` usa `onsetThreshold = 1.5`. Se a música é muito suave, reduza para `1.2`.

### Se há "jitter" no gráfico
- Aumente `RING_CAPACITY` (de 2048 para 4096) — dá mais margem para variação de latência do sistema.
- Reduza `FFT_PERIOD_MS` para 5ms — menor latência, mas maior consumo de CPU.

---

## 5. Arquivos Entregues

- `audio/RingBufferKt.kt` — buffer circular
- `audio/KissFftBeatDetectorKt.kt` — FFT + detecção de batidas
- `audio/BeatEvent.kt` — data class do evento
- `audio/AudioEngineKt.kt` — pipeline completo (ExoPlayer + FFT + events)
- `audio/BeatUiController.kt` — controller Compose / StateFlow
- `audio/ComposeIntegrationExample.kt` — exemplo Compose
- `audio/AudioEngineIntegration.kt` — exemplo de integração com MainActivity XML
- `build.gradle` atualizado (Compose + lifecycle + Media3)
- `TEST_INSTRUCOES.md` — este arquivo
