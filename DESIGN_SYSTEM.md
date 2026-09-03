# BeatTrack — Sistema de Design & Guia de Estilo

> **Documento de referência para IA e desenvolvedores.** Este arquivo define os tokens, componentes, padrões e regras de qualidade do aplicativo. Qualquer modelo de IA (ou pessoa) que gerar/modificar telas deste app DEVE seguir este documento. Ao gerar novas telas, comece copiando os tokens da Seção 3 e os componentes da Seção 4.

---

## 0. Como usar este documento (para modelos de IA)

1. **Nunca invente cores, tamanhos ou espaçamentos fora dos tokens** definidos aqui. Se precisar de um valor novo, registre-o primeiro neste arquivo.
2. **Dark-first**: a experiência primária é o tema escuro. Temas claros são opcionais e devem reutilizar a mesma estrutura de tokens.
3. Todo componente tem uma **receita** (anatomia + propriedades). Copie a receita, não reimagine.
4. Antes de entregar qualquer tela, rode o **Checklist de Qualidade** (Seção 8).
5. Tecnologia-alvo: **Android nativo (Kotlin + Views) com Material Design 3**. Os conceitos (tokens, hierarquia, motion) são portáveis para Compose/web.

---

## 1. Filosofia & Referências de Mercado

O design do BeatTrack sintetiza o que a pesquisa de mercado (2024–2026) apontou como os padrões mais bem aceitos nos players de música de referência:

| Referência | O que pegamos | Por quê |
|---|---|---|
| **Spotify** | Dark theme como padrão, superfícies em camadas (base → card), acento verde vibrante, hierarquia "agora tocando" sempre visível | App de música mais usado do mundo; dark mode com acento único é o padrão de ouro do mercado |
| **Apple Music** | Tipografia gigante como elemento herói, minimalismo calmo, respiro entre seções, foco em uma ação principal | Pesquisa e artigos de UX apontam a "calma" da Apple como o melhor design de player para foco |
| **YouTube Music** | Scrubber (slider de progresso) espesso que engrossa ao arrastar, controles centralizados, menos botões permanentes na tela | Redesign 2025 do YTM foi amplamente elogiado ("they finally nailed it") |
| **Poweramp** | Valor numérico dominante (ex.: BPM) como centro visual, feedback fluido, polimento em detalhes | Eleito "rei da experiência" entre players locais Android em comparativos 2026 |
| **Retro Music Player** | Aderência total ao Material You / Material 3, superfícies arredondadas, ícones consistentes | Melhor materialização do M3 em players Android open-source |

### Princípios (em ordem de prioridade)

1. **O conteúdo é o herói** — o número de BPM (ou a arte da faixa) domina a tela; a interface desaparece atrás dele.
2. **Uma cor de acento, zero poluição** — todo o destaque vem do mint (`accent`); nada de arco-íris.
3. **Controles sob o polegar** — ações principais (play/pause, escolher arquivo) ficam na metade inferior da tela.
4. **Feedback imediato** — todo toque gera resposta visual ou háptica em < 100 ms.
5. **Escaneabilidade em 1 segundo** — o usuário deve responder "que valor está ajustado?" e "está tocando?" sem ler nada.

---

## 2. Anatomia da tela principal (padrão "Now Adjusting")

```
┌─────────────────────────────────┐
│  ╭──────────────────────────────╮  │
│  │ (♪)  120                    │  │  ← herói: card BPM à esquerda
│  │       BPM                   │  │     + controles à direita
│  ╰──────────────────────────────╯  │
│  ╭──────────────────────────────╮  │
│  │  [−] [+]                    │  │  ← controles: - e + na mesma linha
│  │  [Original]                 │  │  ← abaixo: reset BPM
│  │  1.00× · tempo original    │  │
│  ╰──────────────────────────────╯  │
│                                 │
│   ────●────────────             │  ← slider primário
│   40                  200       │
│                                 │
│  ╭──────────────────────────────╮  │
│  │ Nome da faixa.mp3            │  │  ← Now Playing card
│  │ Analisando BPM…             │  │
│  │ ────●──────────  0:00/2:34 │  │  ← scrubber + tempos
│  ╰──────────────────────────────╯  │
│                                 │
│  ─── Rodapé fixo ─────────────  │
│  ╭──────────────────────────────╮  │
│  │ Músicas do dispositivo    [≡] │  │  ← header com ordenação
│  │                              │  │
│  │ (▶) Título da música       2:34│  │  ← lista: ícone de play/pause
│  │     Artista                │  │  │     por item, duração
│  │ (♫) Outra música          3:45│  │
│  │     Artista                │  │
│  │ ...                           │  │
│  │ [📁] [↻] [🔁]        (▶)   │  │  ← rodapé: ícones só, sem texto
│  ╰──────────────────────────────╯  │
└─────────────────────────────────┘
```

**Regras de composição:**
- O herói (número/art) ocupa no mínimo 35% da altura útil visível.
- Exatamente **um** FAB por tela (play/pause).
- Estado vazio reutiliza o cartão Now Playing com ícone + instrução — nunca uma tela em branco.
- O status de análise de BPM aparece **apenas** no cartão Now Playing (linha 2). O herói permanece puro.

---

## 3. Tokens

### 3.1 Cores (tema escuro — padrão)

| Token | Hex | Uso |
|---|---|---|
| `bg/base` | `#0A0F14` | Fundo da tela |
| `bg/card` | `#141C24` | Cartões (Now Playing, herói, rodapé) |
| `bg/icon-button` | `#1D2833` | Botões tonais, superfícies internas |
| `accent` | `#2EE6A8` | Acento único: sliders ativos, thumb, FAB, valores destacados |
| `accent/dim` | `#1FAF7E` | Acento em estados pressionados/containers |
| `accent/light` | `#142EE6A8` | Fundo de destaque em ~8% alpha (linhas de música tocando) |
| `on/accent` | `#05221A` | Texto/ícone sobre o acento |
| `text/primary` | `#F1F6F9` | Títulos, número de BPM |
| `text/secondary` | `#93A4B3` | Subtítulos, leitura de estado, artista |
| `text/tertiary` | `#5C6B78` | Metadados, labels min/max, duração |
| `outline` | `#263340` | Trilhas inativas de slider, divisores |

**Regras de contraste:** `text/primary` sobre `bg/base` ≥ 15:1; `text/secondary` ≥ 7:1; `accent` é usado em elementos gráficos e números grandes (≥ 3:1 garantido). Nunca use `text/tertiary` em textos abaixo de 12sp.

**Regra do acento único:** se uma tela precisa de mais de uma cor de destaque, o design está errado. Re force hierarquia com tamanho/peso, não com cor.

### 3.2 Tipografia (Roboto / system sans)

| Token | Especificação | Uso |
|---|---|---|
| `display/hero` | 72–76sp · medium · `tnum` · letterspacing −2% | Número de BPM |
| `headline` | 20sp · bold | Nome do app no header |
| `title` | 15–16sp · medium · singleLine·ellipsize | Nome da faixa |
| `body` | 13–14sp · regular · line-height 20sp | Subtítulos, status |
| `caption` | 11sp · medium · caps · letterspacing +8% | "BPM", min/max do slider, tempos |
| `meta` | 12sp · regular | Leitura de estado (`1.00× · tempo original`) |

- Números que mudam em tempo real **sempre** usam `fontFeatureSettings="tnum"` (dígitos tabulares) para não tremer o layout.
- Números herói usam letterspacing levemente negativo; labels em caps usam positivo.

### 3.3 Espaçamento (grid de 4dp)

`4 · 8 · 12 · 16 · 20 · 24 · 28 · 32 · 48`

- Padding horizontal da tela: **16dp** (base), **28dp** (tablets).
- Padding interno de cartões: **5–14dp** (base), **14dp** (tablets).
- Entre seções lógicas: **16–24dp**. Entre elementos relacionados: **8–12dp**.
- Nunca use valores fora da escala (ex.: 15dp).

### 3.4 Raio, elevação e superfícies

| Token | Valor | Uso |
|---|---|---|
| `radius/card` | 24dp | Cartões |
| `radius/hero` | 28dp | Cartão herói |
| `radius/pill` | 999dp | Botões, chips |
| `elevation/flat` | 0dp | Tudo — profundidade vem de cor, não de sombra |

- Dark theme de player **não usa sombra**: hierarquia = `bg/base` → `bg/card` → `bg/icon-button` (superfícies mais claras = mais "altas").
- O cartão herói usa gradiente sutil (135°, `#1D2935 → #10161D`) + brilho radial de acento a 20% de alpha no canto superior. Isso é a única "decoração" permitida.

### 3.5 Ícones

- Material Symbols / vetores próprios, viewport 24dp, stroke filled.
- Tamanhos: 16–18dp (dentro de botões), 32dp (ilustrativo), 48dp+ (empty state).
- Cor: `text/secondary` por padrão, `accent` quando ilustrativo/ativo, `on/accent` dentro do FAB.
- Todo ícone clicável tem `contentDescription` (PT-BR) definido em `strings.xml`.

### 3.6 Motion

| Token | Valor | Uso |
|---|---|---|
| `motion/quick` | 150ms · decelerate(1.5) | Troca de ícone, ripple |
| `motion/standard` | 200ms · decelerate(1.5) | Pulso do valor, mudanças de estado |
| `motion/emphasized` | 300ms · fast-out-slow-in | Entrada de telas/cartões |
| `motion/pulse` | 180ms · scale 1.0 → 1.05 → 1.0 | Valor de BPM ao atualizar |

- **Haptics**: `VIRTUAL_KEY` em +/−, reset, play/pause e itens da lista. Nunca em cada tick do slider.
- Nada de animações > 300 ms ou loops infinitos (exceto buffering do player).

---

## 4. Componentes (receitas)

### 4.1 Slider primário (controle de BPM)
- `com.google.android.material.slider.Slider`, `valueFrom/min` → `valueTo/max`, `stepSize=1`.
- `trackHeight=6dp`, `tickVisible=false`, `labelBehavior=floating`, label formatado (`"128 BPM"`).
- Cores: ativo `accent`, inativo `outline`, thumb `accent`.
- Min/max em `caption` (tertiary) nas extremidades, logo abaixo.
- Mudanças aplicam **em tempo real** durante o arrasto (`fromUser == true`).

### 4.2 Botão de ícone (ajuste fino)
- Estilo `Widget.Material3.Button.IconButton.Filled`, 36×36dp, raio 18dp.
- Fundo `bg/icon-button`, ícone 14sp `text/primary`.
- Sempre com `contentDescription` e háptica no clique.

### 4.3 Botão reset (BPM original)
- Estilo `Widget.Material3.Button.TonalButton`, altura 32dp, raio 12dp.
- Fundo `bg/icon-button`, texto 10sp medium `text/primary`.
- Texto: "Original".
- Posicionado **abaixo** da linha de botões − e +.

### 4.4 FAB de play/pause
- `FloatingActionButton` 56dp, fundo `accent`, ícone `on/accent`.
- Ícone troca `ic_play` ⇄ `ic_pause` conforme o estado real do player (listener, não na intenção).
- `contentDescription` troca junto ("Reproduzir"/"Pausar").
- Desabilitado até `Player.STATE_READY`.

### 4.5 Cartão Now Playing
- Fundo `bg/card`, raio `radius/card`, padding 5dp (base) / 14dp (tablet).
- Linha 1: título da faixa (`title`, singleLine, ellipsize=middle).
- Linha 2: status (`meta`, `text/secondary`) — vazio / analisando / BPM detectado.
- Scrubber: slider 4–6dp, `0..1000`, desabilitado sem mídia; tempos `caption` nas pontas (`0:00` / `--:--` até READY).
- **Estado vazio**: título "Nenhuma música selecionada", status com instrução; é o mesmo cartão, sem layout alternativo.

### 4.6 Cartão herói (valor dominante)
- Fundo `bg/hero` (gradiente — ver 3.4), raio 28dp, padding 10dp, minHeight 41–150dp (responsivo).
- Conteúdo: círculo `accent/wash` 16–64dp com ícone musical 8–32dp `accent` → número `display/hero` `text/primary` → label `caption` caps `text/secondary`.
- O número pulsa (`motion/pulse`) a cada atualização.
- **Layout horizontal**: card BPM à esquerda + controles (−, +, reset, tempo factor) à direita.

### 4.7 Lista de músicas do dispositivo
- `RecyclerView` com `LinearLayoutManager`.
- Cada item: ícone (40dp) + título + artista + duração + handle de drag (modo manual).
- Ícone de item:
  - Tocando → `ic_pause` em `accent`
  - Selecionado e pausado → `ic_play` em `text_secondary`
  - Não selecionado → `ic_music_note` em `text_tertiary`
- Toque único = selecionar; duplo toque = tocar.
- Ícone é clicável: se tocando → pausa; se não → inicia reprodução.
- Modo de ordenação: **Título** (A→Z), **Artista** (A→Z + título), **Manual** (drag com long-press).
- Handle de drag (`ic_drag`) visível apenas no modo **Manual**.
- Estado vazio: texto centrado ("Nenhuma música encontrada no dispositivo" ou pedido de permissão).
- Altura fixa: 140dp (base), 280dp (tablet).

### 4.8 Botões do rodapé (ações)
- Estilo `Widget.Material3.Button.IconButton.Filled`, 40×40dp, raio 20dp, **sem texto**.
- Fundo `bg/icon-button`, ícone 18dp `text_secondary` (desligado) / `accent` (ativo).
- 4 botões: **Localizar** (`ic_folder`), **Repetir música atual** (`ic_repeat`), **Tocar todas** (`ic_playlist`), + **FAB play** à direita.
- Exclusão mútua: "Repetir" e "Tocar todas" não podem estar ativos ao mesmo tempo.

### 4.9 Feedback textual
- Toasts curtos para eventos assíncronos (resultado da detecção de BPM).
- Nenhum diálogo/modal nesta fase — estados são comunicados inline (linha de status).

---

## 5. Padrões de implementação Android

- **Views + ViewBinding** (não Compose nesta fase). Nomes de IDs em `lowerCamelCase` semânticos (`bpmValue`, `trackSlider`, `songList`, `selectButton`, `repeatButton`, `loopAllButton`, `playFab`, `bpmMinusButton`, `bpmPlusButton`, `bpmResetButton`).
- **Theme**: `Theme.Material3.Dark.NoActionBar`. `colorPrimary=accent`, `colorSecondaryContainer=bg/icon-button`, `colorOnSecondaryContainer=text/primary`.
- **System bars**: edge-to-edge com `WindowCompat.setDecorFitsSystemWindows=false`; padding via `WindowInsetsCompat.Type.systemBars()`.
- **Strings**: todo texto de usuário em `strings.xml` (PT-BR). Nunca hardcode em layout/código.
- **Formatação numérica**: `Locale.US` para valores técnicos (BPM, tempos `m:ss`).
- **Permissões**: `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (API < 33), solicitadas no `onCreate`. Fluxo: se concedida → carrega músicas; se negada → mostra lista vazia com instrução.
- **Player**: Media3 ExoPlayer 1.3.1, streaming completo (sem decodificação total em memória), velocidade via `setPlaybackSpeed` (Sonic time-stretch, pitch preservado).
- **Estado de BPM**: `detectedBpm = 0` = não detectado; velocidade = alvo/referência (referência = detectado ou valor atual do slider).
- **Reprodução**:
  - `REPEAT_MODE_ONE` quando "Repetir música atual" está ativo.
  - `playNextInQueue()` avança para próxima música; quando `isLoopingAll=true` e na última → volta à primeira.
  - Exclusão mútua: ativar um desativa o outro.
- **GestureDetector**: `onDown` retorna `true`; `onSingleTapConfirmed` = selecionar; `onDoubleTap` = tocar. Implementado via `RecyclerView.OnItemTouchListener` com `onInterceptTouchEvent` e `onTouchEvent` (não `SimpleOnItemTouchListener`).
- **Drag-and-drop**: `ItemTouchHelper.SimpleCallback(UP|DOWN, 0)`, `isLongPressDragEnabled` retorna `true` apenas no modo **Manual**.

---

## 6. Acessibilidade

- Alvos de toque ≥ 48×48dp (FAB e botões de ícone têm 56/40dp).
- Contraste verificado na tabela 3.1.
- `contentDescription` em todo ícone interativo; sliders anunciam valor via label flutuante formatado.
- Textos não dependem só de cor: estado do player é ícone + descrição; detecção de BPM é texto explícito.
- Háptica como reforço, nunca como única resposta.

---

## 7. Qualidade — o que coloca o app entre os melhores

1. **Zero travamento de UI**: decodificação/detecção de BPM em thread de fundo; player em streaming; atualização de posição via `Handler` de 500 ms.
2. **Tempo real de verdade**: BPM aplica durante o arrasto, sem soltar o dedo; +/- em passos de 1 BPM.
3. **Pitch preservado**: Sonic time-stretch do ExoPlayer — acelera sem "espécie de hamster".
4. **Estados completos**: vazio, analisando, detectado, não-detectado, tocando, pausado, fim, loop-all — todos comunicados.
5. **Consistência visual**: nenhum valor fora dos tokens; nenhum texto fora de `strings.xml`.
6. **Responsividade**: dimens variant para h500dp, h600dp, h800dp, sw720dp.

---

## 8. Checklist antes de entregar qualquer tela

- [ ] Só usei tokens das Seções 3 (cores, tipo, espaçamento, raio, motion)?
- [ ] Dark-first respeitado e acento único (`#2EE6A8`) sem concorrência?
- [ ] Exatamente um FAB; ações principais na metade inferior?
- [ ] Herói visível (número/art) com ≥ 35% da altura útil?
- [ ] Todos os ícones com `contentDescription` e alvo ≥ 48dp?
- [ ] Háptica nos botões de ajuste; nada de animação > 300 ms?
- [ ] Estado vazio tratado (sem tela branca)?
- [ ] Textos em `strings.xml`; números tabulares onde contam em tempo real?
- [ ] Rodou o checklist de qualidade da Seção 7 (sem trabalho na main thread)?
- [ ] RecyclerView com `LinearLayoutManager` configurado?
- [ ] Exclusão mútua entre "Repetir" e "Tocar todas" implementada?

---

## 9. Histórico

| Versão | Data | Mudança |
|---|---|---|
| 1.0 | 2026-09-01 | Criação do design system a partir da pesquisa de mercado (Spotify, Apple Music, YouTube Music, Poweramp, Retro Music; Material 3/Material You). |
| 1.1 | 2026-09-03 | Hero card com controles à direita em layout horizontal. Botão "Original" abaixo de − e +. Botões do rodapé agora só ícone (IconButton.Filled, sem texto). Botão "Tocar todas em sequência" adicionado com exclusão mútua com "Repetir". Lista de músicas do dispositivo com RecyclerView funcional, double-tap para tocar, ícone play/pause por item. Tempo atual/total em tempo real. |
