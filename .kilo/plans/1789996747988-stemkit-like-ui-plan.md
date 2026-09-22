# Plano: StemKit-like UI + Separação de Stems no Android

## Decisões técnicas tomadas

- **Modelo ONNX**: `htdemucs.onnx` (4 stems: drums, bass, other, vocals, 316 MB) embutido no APK via `assets/models/`
- **Inferência**: ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-mobile`)
- **Fluxo**: separação on-demand ao selecionar música, cache em disco
- **Reprodução de stems**: 1 ExoPlayer master + 4 ExoPlayers de stem sincronizados por `MediaSession`/handler comum
- **Layout**: Navigation Drawer (abre/fecha com hamburger/swipe), lista de músicas no drawer, área principal com BPM/player/stems
- **Processamento**: background thread com overlap-add para áudio > 7.8s

---

## Estrutura do plano

### Fase 0 — Preparação (build + assets)

**Arquivos afetados**: `app/build.gradle`

1. Adicionar dependência ONNX Runtime Mobile no `build.gradle`:
   ```
   implementation 'com.microsoft.onnxruntime:onnxruntime-mobile:1.17.0'
   ```
2. Colocar `htdemucs.onnx` em `app/src/main/assets/models/htdemucs.onnx` (316 MB).
   - Fonte: https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs.onnx
3. Adicionar `htdemucs.onnx` ao `.gitignore` ou usar Git LFS (arquivo grande).

---

### Fase 1 — Engine de separação (SeparationEngine)

**Novo arquivo**: `app/src/main/kotlin/.../stem/SeparationEngine.kt`

Responsabilidades:
- Carregar modelo ONNX dos assets na primeira execução (copy para `filesDir/models/htdemucs.onnx`)
- Receber arquivo de áudio decodificado em PCM float32 stereo, 44.1kHz
- Aplicar overlap-add em chunks de 7.8s (343980 samples) com hop de 50% (stereo shape: `[1, 2, 343980]`)
- Rodar inferência ONNX para cada chunk
- Reconstruir áudio completo dos stems (overlap-add com janela Hann)
- Salvar stems como WAV temporários em `cacheDir/stems/<hash>/{drums,bass,other,vocals}.wav`
- Retornar callback de progresso (0–100%) durante processamento

**Dependências**: `onnxruntime-mobile`, `kotlinx-coroutines`

**Detalhes técnicos**:
- Input tensor: `mix` float32 shape `[1, 2, 343980]`, valores em `[-1, 1]`
- Output tensor: `stems` float32 shape `[1, 4, 2, 343980]` — ordem: drums, bass, other, vocals
- Janela Hann para overlap-add: `w[n] = 0.5 * (1 - cos(2*pi*n/(N-1)))`
- Hop: 50% do frame (171990 samples) para garantir smoothness

---

### Fase 2 — Modelos de dados e repositório de stems

**Novo arquivo**: `app/src/main/kotlin/.../stem/StemsCache.kt`

Responsabilidades:
- Gerenciar cache de stems em disco (`cacheDir/stems/<hash>/`)
- Verificar se stems já existem para uma música (hash do arquivo original)
- Limpar cache antigo (manter apenas N mais recentes ou por tamanho)
- Fornecer paths dos stems para um dado `Uri`

**Novo arquivo**: `app/src/main/kotlin/.../stem/StemTrack.kt`

```kotlin
data class StemTrack(
    val name: String,        // "Drums", "Bass", "Other", "Vocals"
    val filePath: String,    // caminho do WAV em cache
    val colorRes: Int,       // cor do ícone/indicador
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val volume: Float = 1.0f
)
```

---

### Fase 3 — Sincronização de múltiplos players

**Novo arquivo**: `app/src/main/kotlin/.../stem/StemPlayerSync.kt`

Responsabilidades:
- Gerenciar 4 ExoPlayers (um por stem) + 1 ExoPlayer master (mix original)
- Sincronizar posição de todos os players a cada ~100ms via `Handler`
- Aplicar mute/solo/volume por stem em tempo real
- Broadcast estado via `MediaSession` para integração com controles do sistema
- Gerenciar ciclo de vida: criar/release players ao trocar de música

**Interface pública**:
```kotlin
class StemPlayerSync(context: Context) {
    fun prepareStems(stemPaths: List<String>, originalUri: Uri)
    fun play(), pause(), seekTo(positionMs: Long)
    fun setStemVolume(stemIndex: Int, volume: Float)
    fun muteStem(stemIndex: Int), soloStem(stemIndex: Int)
    fun release()
    fun currentPosition: Long
    fun duration: Long
}
```

---

### Fase 4 — Redesign da UI (StemKit-like)

#### 4.1 Layout principal com Drawer

**Novo arquivo**: `app/src/main/res/layout/activity_main_stemkit.xml`

Estrutura:
```xml
<androidx.drawerlayout.widget.DrawerLayout>
  <!-- Conteúdo principal (direita) -->
  <LinearLayout orientation="vertical">
    <!-- Toolbar com hamburger button -->
    <com.google.android.material.appbar.MaterialToolbar>
      <ImageButton id="hamburger" />
    </com.google.android.material.appbar.MaterialToolbar>

    <ScrollView>
      <!-- Hero Card: BPM + controles (horizontal) -->
      <!-- Track Player Card (now playing + scrubber) -->
      <!-- Stems Section (RecyclerView horizontal/vertical com faders) -->
    </ScrollView>

    <!-- Footer fixo: mini-controles + info -->
  </LinearLayout>

  <!-- Drawer (esquerda) -->
  <LinearLayout width="320dp" background="@color/bg_card">
    <TextView>Músicas do dispositivo</TextView>
    <RecyclerView id="drawerSongList" />
  </LinearLayout>
</androidx.drawerlayout.widget.DrawerLayout>
```

#### 4.2 Stems Section (novo item de layout)

**Novo arquivo**: `app/src/main/res/layout/item_stem.xml`

Cada stem mostra:
- Nome do stem (drums, bass, other, vocals)
- Ícone colorido
- Mute/Solo buttons (pequenos, 32dp)
- Volume slider horizontal (miniatura)
- Indicador de ativo/separando

#### 4.3 StemsAdapter

**Novo arquivo**: `stem/StemsAdapter.kt` — RecyclerView adapter para a lista de stems com mute/solo/volume

#### 4.4 Modificações em MainActivity

- Trocar layout para `activity_main_stemkit.xml`
- Setup DrawerLayout + hamburger toggle
- Mover `SongAdapter` e RecyclerView de músicas para dentro do drawer
- Adicionar StemsAdapter + RecyclerView de stems no conteúdo principal
- Integrar `SeparationEngine` e `StemPlayerSync`
- Estados: `Idle → Separating → Ready → Playing` com progresso visível

**Novos estados na UI**:
- `Separating (0-100%)`: mostrar progresso no lugar dos stems
- `Ready`: stems aparecem com faders desabilitados até play
- `Playing`: faders ativos, sync ativo

---

### Fase 5 — Integração do fluxo

Fluxo completo:
```
Usuário abre drawer → seleciona música → 
MainActivity.onFilePicked(uri) →
  1. Inicia playback master (ExoPlayer) IMEDIATAMENTE
  2. Mostra "Separando faixas... X%" no stem section
  3. Background: decode PCM → SeparationEngine.separate(pcm, progressCallback)
  4. Engine salva stems em cacheDir/stems/<hash>/
  5. On complete: StemPlayerSync.prepareStems(paths, uri)
  6. UI: substitui progresso por StemsAdapter com 4 stems
  7. Playback master + stems sincronizados
```

---

### Fase 6 — Testes e validação

1. **Unit tests**:
   - `SeparationEngineTest`: teste com áudio sintético (sinusoidiasKnown BPM) para verificar shapes e ordem dos stems
   - `StemsCacheTest`: hash, salvar/recuperar, limpar cache
   - `StemPlayerSyncTest`: sync de posição, mute/solo aplicam corretamente

2. **Instrumented tests**:
   - Selecionar música do dispositivo → verificar que stems aparecem após separação
   - Play/pause → verificar sync entre master e stems
   - Mute drums → verificar que drums some do mix
   - Drawer abre/fecha com gesture e botão

3. **Performance targets**:
   - Inferência de 3min de áudio: < 60s em CPU (Snapdragon 778G+)
   - Memória peak: < 500 MB durante separação
   - Drawer abrindo: < 100ms

---

### Fase 7 — Polimento e edge cases

1. **Primeira execução**: copiar modelo ONNX de assets para `filesDir` (~10s, mostrar progresso)
2. **APK size**: 316 MB modelo + app (~15 MB) = ~331 MB total. Avisar usuário no Google Play listing.
3. **Android 13+**: confirmar permissão `READ_MEDIA_AUDIO` antes de acessar arquivos
4. **Low memory**: se sistema pedir, liberar cache de stems mais antigas
5. **Cancelamento**: permitir cancelar separação em andamento (botão X no progresso)
6. **Erro de modelo**: se ONNX falhar ao carregar, mostrar mensagem e fallback para player sem stems

---

## Ordem de implementação recomendada

1. Fase 0 (build + assets)
2. Fase 1 (SeparationEngine) — mais crítico, requer testes de corretude
3. Fase 3 (StemPlayerSync) — paralelo à Fase 1
4. Fase 4 (UI com drawer e stems section)
5. Fase 2 (StemsCache) — rápido, dependência da Fase 1
6. Fase 5 (integração do fluxo)
7. Fase 6 e 7 (testes + edge cases)

---

## Riscos e mitigações

| Risco | Mitigação |
|-------|-----------|
| APK muito grande (331 MB) | Google Play suporta até 150 MB nativamente + expansion files. Usar Android App Bundle (.aab) para dynamic delivery. |
| Inferência lenta em CPU | Usar NNAPI delegate do ONNX Runtime para acelerar em DSP/NPU de dispositivos modernos |
| Sincronização drift entre players | Sincronizar via `MediaSession` + re-sync a cada 5s; aceitar drift < 50ms |
| Memória insuficiente | Processar em chunks pequenos, liberar referências imediatamente |
| Modelo ONNX incompatível | Fixar opset 17 no modelo; validar em CI com emulador x86_64 |

---

## Arquivos existentes que NÃO mudam

- `AudioDecoder.kt` (continua fornecendo PCM para BPM e agora também para stems)
- `BpmDetector.kt` (inalterado)
- `SongAdapter.kt`, `ItemSongBinding` (movem para o drawer)
- `colors.xml`, `strings.xml` (estendidos, não reescritos)

---

## Arquivos novos criados

| Arquivo | Responsabilidade |
|---------|-----------------|
| `stem/SeparationEngine.kt` | ONNX inference + overlap-add |
| `stem/StemsCache.kt` | Gerenciamento de cache de stems em disco |
| `stem/StemTrack.kt` | Modelo de dados de um stem |
| `stem/StemPlayerSync.kt` | 4+1 ExoPlayers sincronizados |
| `stem/StemsAdapter.kt` | RecyclerView adapter para UI de stems |
| `res/layout/activity_main_stemkit.xml` | Layout principal com Drawer |
| `res/layout/item_stem.xml` | Layout de item de stem (fader + controles) |
| `res/menu/drawer_menu.xml` | Menu do Navigation Drawer |
