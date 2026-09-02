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
│  BeatTrack                      │  ← header: nome curto + subtítulo discreto
│  Tempo real para músicas locais │
│                                 │
│  ╭───────────────────────────╮  │
│  │        (♪)                │  │  ← herói: cartão com gradiente,
│  │         120               │  │     número gigante centralizado,
│  │        BPM                │  │     legenda em caps espaçada
│  ╰───────────────────────────╯  │
│                                 │
│   ────●────────────             │  ← slider primário (controle herói)
│   40                  200       │     min/max nas extremidades
│                                 │
│      [−]   [+]   [Original]     │  ← ajuste fino + reset
│      1.00× · tempo original     │  ← leitura de estado (pill de texto)
│                                 │
│  ╭───────────────────────────╮  │
│  │ Nome da faixa.mp3         │  │  ← cartão "Now Playing":
│  │ Analisando BPM…           │  │     título + status,
│  │ ────●──────────  2:34     │  │     scrubber + tempos,
│  │ [Escolher música]   (▶)   │  │     ação secundária + play FAB
│  ╰───────────────────────────╯  │
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
| `bg/card` | `#141C24` | Cartões (Now Playing, herói) |
| `bg/icon-button` | `#1D2833` | Botões tonais, superfícies internas |
| `accent` | `#2EE6A8` | Acento único: sliders ativos, thumb, FAB, valores destacados |
| `accent/dim` | `#1FAF7E` | Acento em estados pressionados/containers |
| `on/accent` | `#05221A` | Texto/ícone sobre o acento |
| `text/primary` | `#F1F6F9` | Títulos, número de BPM |
| `text/secondary` | `#93A4B3` | Subtítulos, leitura de estado |
| `text/tertiary` | `#5C6B78` | Metadados, labels min/max |
| `outline` | `#263340` | Trilhas inativas de slider, divisores |
| `accent/wash` | `#142EE6A8` | Fundos circulares com acento a ~8% |

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

- Padding horizontal da tela: **20dp**.
- Padding interno de cartões: **20–24dp**.
- Entre seções lógicas: **24–28dp**. Entre elementos relacionados: **8–12dp**.
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
- Tamanhos: 20dp (dentro de botões), 32dp (ilustrativo), 48dp+ (empty state).
- Cor: `text/secondary` por padrão, `accent` quando ilustrativo/ativo, `on/accent` dentro do FAB.
- Todo ícone clicável tem `contentDescription` (PT-BR) definido em `strings.xml`.

### 3.6 Motion

| Token | Valor | Uso |
|---|---|---|
| `motion/quick` | 150ms · decelerate(1.5) | Troca de ícone, ripple |
| `motion/standard` | 200ms · decelerate(1.5) | Pulso do valor, mudanças de estado |
| `motion/emphasized` | 300ms · fast-out-slow-in | Entrada de telas/cartões |
| `motion/pulse` | 180ms · scale 1.0 → 1.05 → 1.0 | Valor de BPM ao atualizar |

- **Haptics**: `VIRTUAL_KEY` em +/−, reset e play/pause. Nunca em cada tick do slider.
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
- Estilo `Widget.Material3.Button.IconButton.Filled`, 48×48dp, raio pill.
- Fundo `bg/icon-button`, ícone 20dp `text/primary`.
- Sempre com `contentDescription` e háptica no clique.

### 4.3 Botão tonal (ação secundária)
- Estilo `Widget.Material3.Button.TonalButton`, altura 40dp, raio pill, texto 14sp medium.
- Fundo `bg/icon-button`, texto `text/primary`, ícone opcional.
- **Um único** botão tonal por linha.

### 4.4 FAB de play/pause
- `FloatingActionButton` 56dp, fundo `accent`, ícone `on/accent`.
- Ícone troca `ic_play` ⇄ `ic_pause` conforme o estado real do player (listener, não na intenção).
- `contentDescription` troca junto ("Reproduzir"/"Pausar").
- Desabilitado até `Player.STATE_READY`.

### 4.5 Cartão Now Playing
- Fundo `bg/card`, raio `radius/card`, padding 20dp.
- Linha 1: título da faixa (`title`, singleLine, ellipsize=middle).
- Linha 2: status (`meta`, `text/secondary`) — vazio / analisando / BPM detectado.
- Scrubber: slider 4–6dp, `0..1000`, desabilitado sem mídia; tempos `caption` nas pontas (`0:00` / `--:--` até READY).
- Rodapé: botão "Escolher música" (tonal + ícone pasta) à esquerda, FAB à direita, espaço elástico entre eles.
- **Estado vazio**: título "Nenhuma música selecionada", status com instrução; é o mesmo cartão, sem layout alternativo.

### 4.6 Cartão herói (valor dominante)
- Fundo `bg/hero` (gradiente + brilho — ver 3.4), raio 28dp, padding 28dp, minHeight 280dp, gravity center.
- Conteúdo: círculo `accent/wash` 64dp com ícone musical 32dp `accent` → número `display/hero` `text/primary` → label `caption` caps `text/secondary`.
- O número pulsa (`motion/pulse`) a cada atualização.

### 4.7 Feedback textual
- Toasts curtos para eventos assíncronos (resultado da detecção de BPM).
- Nenhum diálogo/modal nesta fase — estados são comunicados inline (linha de status).

---

## 5. Padrões de implementação Android

- **Views + ViewBinding** (não Compose nesta fase). Nomes de IDs em `lowerCamelCase` semânticos (`bpmValue`, `trackSlider`).
- **Theme**: `Theme.Material3.Dark.NoActionBar`. `colorPrimary=accent`, `colorSecondaryContainer=bg/icon-button`, `colorOnSecondaryContainer=text/primary`.
- **System bars**: status e navigation bar na cor `bg/base`, `windowLightStatusBar=false` — sem edge-to-edge transparente até haver arte de fundo que justifique.
- **Strings**: todo texto de usuário em `strings.xml` (PT-BR). Nunca hardcode em layout/código.
- **Formatação numérica**: `Locale.US` para valores técnicos (BPM, tempos `m:ss`).
- **Permissões**: fluxo intacto — `READ_MEDIA_AUDIO` (33+) / `READ_EXTERNAL_STORAGE` (≤32), `ActivityResultContracts.GetContent` com `audio/*`.
- **Player**: Media3 ExoPlayer, streaming completo (sem decodificação total em memória), velocidade via `setPlaybackSpeed` (Sonic time-stretch, pitch preservado).
- **Estado de BPM**: `detectedBpm = 0` = não detectado; velocidade = alvo/referência (referência = detectado ou valor atual do slider).

---

## 6. Acessibilidade

- Alvos de toque ≥ 48×48dp (FAB e botões de ícone têm 56/48dp).
- Contraste verificado na tabela 3.1.
- `contentDescription` em todo ícone interativo; sliders anunciam valor via label flutuante formatado.
- Textos não dependem só de cor: estado do player é ícone + descrição; detecção de BPM é texto explícito.
- Háptica como reforço, nunca como única resposta.

---

## 7. Qualidade — o que coloca o app entre os melhores

1. **Zero travamento de UI**: decodificação/detecção de BPM em thread de fundo; player em streaming; atualização de posição via `Handler` de 500 ms.
2. **Tempo real de verdade**: BPM aplica durante o arrasto, sem soltar o dedo; +/- em passos de 1 BPM.
3. **Pitch preservado**: Sonic time-stretch do ExoPlayer — acelera sem "espécie de hamster".
4. **Estados completos**: vazio, analisando, detectado, não-detectado, tocando, pausado, fim — todos comunicados.
5. **Consistência visual**: nenhum valor fora dos tokens; nenhum texto fora de `strings.xml`.

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

---

## 9. Histórico

| Versão | Data | Mudança |
|---|---|---|
| 1.0 | 2026-09-01 | Criação do design system a partir da pesquisa de mercado (Spotify, Apple Music, YouTube Music, Poweramp, Retro Music; Material 3/Material You). Redesign completo da tela principal. |
