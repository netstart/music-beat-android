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
| **Material 3 Expressive** (Android 16, Pixel, Android Auto 2025–26) | Slider com trilha grossa, alça em pílula vertical e ponto de fim | Novo padrão de player do Android; progresso legível num relance |
| **Metrônomos e apps de DJ** (djay, Ultimate Guitar) | Valor grande no centro com −/+ dos lados e slider logo abaixo | Combina ajuste rápido (slider) com ajuste preciso (±1) |

### Princípios (em ordem de prioridade)

1. **O conteúdo é o herói** — o número de BPM (ou a arte da faixa) domina a tela; a interface desaparece atrás dele.
2. **Uma cor de acento para ação** — todo destaque interativo vem do mint (`accent`). Cores extras só para **identificar dados** (ex.: seções da música, 3.1.1), nunca para botões ou estados.
3. **Controles sob o polegar** — ações principais (play/pause, escolher arquivo) ficam na metade inferior da tela.
4. **Feedback imediato** — todo toque gera resposta visual ou háptica em < 100 ms.
5. **Escaneabilidade em 1 segundo** — o usuário deve responder "que valor está ajustado?" e "está tocando?" sem ler nada.

---

## 2. Anatomia inicial da tela principal, as não limitada, esta pode mudar e ser atualizada (padrão "Now Adjusting")

```
┌─────────────────────────────────┐
│  ╭──────────────────────────────╮  │
│  │             BPM              │  │  ← herói centrado
│  │  (−)        120         (+)  │  │  ← ajuste fino nas laterais (48dp)
│  │   (Original) 1.00× · tempo   │  │  ← reset + fator na mesma linha
│  ╰──────────────────────────────╯  │
│                                 │
│   ━━━━━━━━━━┃━━━━━━━━━━━•       │  ← slider expressivo (trilha 16dp)
│   40                  200       │
│                                 │
│  ╭──────────────────────────────╮  │
│  │ Nome da faixa.mp3            │  │  ← Now Playing card
│  │ Analisando BPM…             │  │
│  │ ────●──────────  0:00/2:34 │  │  ← scrubber + tempos
│  ╰──────────────────────────────╯  │
│                                 │
│  [ banner AdMob ]                  │  ← último item da área que rola
│                                 │
│  ╭──────── ▬ ─────────────────────╮  │  ← painel de músicas (arrastável)
│  │ MÚSICAS DO DISPOSITIVO · 12  ≡ │  │     recolhido: só este cabeçalho
│  ╰──────────────────────────────╯  │     aberto: lista até a toolbar
│  [📁] [↻] [🔁]      [⏮] (▶) [⏭]  │  ← rodapé fixo por cima do painel
└─────────────────────────────────┘
```

**Regras de composição:**
- O herói (número/art) é o maior texto da tela e fica no topo; compacto (1.5) para deixar espaço ao Now Playing e à Estrutura.
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

**Regra do acento único (para ação):** botões, sliders, FAB, ícones ativos e "o que está tocando agora" usam só `accent`. Hierarquia vem de tamanho/peso, não de cor.

#### 3.1.1 Paleta de dados (seções da música)

Cores categóricas para **identificar dados** (uma cor por tipo de seção, como legenda), não para ação. Todas com contraste ≥ 3:1 sobre `bg/card`, distintas entre si e do `accent`.

| Token | Hex | Seção |
|---|---|---|
| `section/intro` | `#5B8DEF` | Intro |
| `section/verse` | `#E5B83B` | Verso |
| `section/pre-chorus` | `#F08A4B` | Pré-refrão |
| `section/chorus` | `#EF5D8B` | Refrão |
| `section/bridge` | `#A77BF3` | Ponte |
| `section/outro` | `#8A9BA8` | Final |

- Seção atual: cor cheia; demais: mesma cor a 40% de alpha.
- A cor nunca é a única pista: o nome da seção sempre aparece em texto.
- Nova categoria de dado = nova linha aqui antes de usar.

### 3.2 Tipografia (Roboto / system sans)

| Token | Especificação | Uso |
|---|---|---|
| `display/hero` | 45–53sp · medium · `tnum` · letterspacing −2% · sem font padding | Número de BPM |
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
- Estilo `BeatSlider` (M3 Expressive, Material ≥ 1.13): alça em pílula vertical `thumbWidth=4dp` × `thumbHeight=44dp`, vão `thumbTrackGapSize=6dp`, ponto de fim `trackStopIndicatorSize=4dp`.
- BPM: `trackHeight=16dp`. Scrubber da faixa: `trackHeight=10dp`, `thumbHeight=32dp`.
- `tickVisible=false`, `labelBehavior=floating`, label formatado (`"128 BPM"`).
- Cores: ativo `accent`, inativo `outline`, thumb `accent`.
- Min/max em `caption` (tertiary) nas extremidades, logo abaixo.
- Mudanças aplicam **em tempo real** durante o arrasto (`fromUser == true`).

### 4.2 Botão de ícone (ajuste fino)
- Estilo `Widget.Material3.Button.IconButton.Filled`, 48×48dp, raio 24dp.
- Fundo `bg/icon-button`, ícone 18dp `text/primary`.
- Sempre com `contentDescription` e háptica no clique.

### 4.3 Botão reset (BPM original)
- Estilo `Widget.Material3.Button.TonalButton`, altura 48dp, raio `radius/pill`.
- Fundo `bg/icon-button`, texto 12sp medium `text/primary`.
- Texto: "Original".
- Posicionado **abaixo** do número, na mesma linha do fator de tempo (`meta`).

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
- Conteúdo, centrado: label `caption` caps "BPM" → linha `(−) número (+)` com o número `display/hero` `text/primary` ocupando o meio → linha `(Original)` + fator de tempo.
- O número pulsa (`motion/pulse`) a cada atualização.
- Sem ícone decorativo: o número é o herói.

### 4.7 Lista de músicas do dispositivo (painel inferior)
- Fica num **painel inferior arrastável** (`BottomSheetBehavior` num `CoordinatorLayout`), não em menu lateral — padrão "A seguir" do YouTube Music e da fila do Apple Music; o Material desaconselha drawer em telefone.
- Recolhido: alça (32×4dp, `text/tertiary`) + cabeçalho "MÚSICAS DO DISPOSITIVO · N" (`caption`) e a ordenação, logo acima do rodapé (`sheet_peek` = 72dp + altura do rodapé).
- Aberto: a lista vai até logo abaixo da toolbar; o rodapé (play, anterior/próxima) continua visível por cima. Arraste, toque no cabeçalho ou "voltar" alternam/recolhem.
- Fundo `bg/card` com raio `radius/card` só em cima (`bg_sheet`).
- `RecyclerView` com `LinearLayoutManager`.
- Cada item: ícone (40dp) + título + artista + duração + handle de drag (modo manual).
- Ícone de item:
  - Tocando → `ic_pause` em `accent`
  - Selecionado e pausado → `ic_play` em `text_secondary`
  - Não selecionado → `ic_music_note` em `text_tertiary`
- **Um toque toca a música** (como em todo player); tocar na que já toca não reinicia.
- Ícone é clicável: se tocando → pausa; se não → inicia reprodução.
- Modo de ordenação: **Título** (A→Z), **Artista** (A→Z + título), **Manual** (drag com long-press).
- Toque no rótulo de ordenação (alvo ≥ 48dp) abre a escolha. Modo e ordem manual ficam salvos no aparelho (`SharedPreferences` `song_sort`); músicas novas entram no fim da ordem manual.
- Handle de drag (`ic_drag`) visível apenas no modo **Manual**.
- Estado vazio: texto centrado ("Nenhuma música encontrada no dispositivo" ou pedido de permissão).

### 4.8 Botões do rodapé (ações)
- Estilo `Widget.Material3.Button.IconButton.Filled`, 40×40dp, raio 20dp, **sem texto**.
- Fundo `bg/icon-button`, ícone 18dp `text_secondary` (desligado) / `accent` (ativo).
- 4 botões: **Localizar** (`ic_folder`), **Repetir música atual** (`ic_repeat`), **Tocar todas** (`ic_playlist`), + **FAB play** à direita.
- Exclusão mútua: "Repetir" e "Tocar todas" não podem estar ativos ao mesmo tempo.

### 4.10 Banner de anúncio (AdMob)
- Banner adaptativo na largura útil, **último item da área que rola** (`adContainer`, depois do cartão Elementos); fica `gone` até carregar.
- Nunca intersticial nem app open: não interromper a música. Plano completo em `docs/MONETIZACAO.md`.
- Consentimento UMP antes de pedir anúncios; item **Privacidade dos anúncios** no menu da toolbar quando exigido.

### 4.11 Cartão Elementos (separação)
- Mesmo cartão `bg_card` do Now Playing; título `title`, status `body` `text_secondary`, progresso `LinearProgressIndicator` `accent`.
- A separação **nunca começa sozinha**: só pelo botão **Separar elementos** (vira **Cancelar** durante o trabalho).
- Trocar de música **não interrompe** a separação: ela termina em segundo plano; o cartão da outra música mostra "Separando outra música… N%" com o botão desabilitado.
- Com os elementos prontos: linhas solo/mudo/volume e o botão **Limpar elementos** (`ic_delete` 18dp `text_secondary`), que apaga os arquivos da música atual e volta ao áudio original sem parar a reprodução.
- Botões tonais `bg/icon-button`, altura 48dp, raio `radius/pill`, lado a lado com 8dp entre eles.

### 4.12 Tela de Configurações
- Activity própria (não diálogo), aberta pelo item **Configurações** do menu da toolbar; toolbar `bg/base` com `ic_arrow_back` ("Voltar").
- Conteúdo em cartões `bg_card` com padding horizontal da tela 16dp; cada cartão: título `title`, explicação `body` `text_secondary`, valor atual `body` `text_primary`, ações em botões tonais de 48dp.
- Hoje: **Elementos separados** — pasta escolhida pelo usuário (Storage Access Framework) ou armazenamento do app.

### 4.13 Cartão Estrutura (seções, frases e contagem)
- Mesmo cartão `bg_card`, entre Now Playing e Elementos; título `title` "Estrutura" + status `body` `text_secondary`.
- A música inteira é analisada **antes** (thread de fundo, depois do BPM); em tempo real só a posição é acompanhada.
- **Barra de seções** (`structureBar`, 48dp): blocos proporcionais à duração, cor da paleta 3.1.1, seção atual cheia, demais a 40%; linha de posição `text_primary` 2dp. Toque numa seção pula para o início dela.
- **Linha de leitura**: nome da seção (`title`, na cor da seção) + "Frase 2 de 4" (`meta`, `tnum`).
- **Contagem 1–8** (`countView`, 40dp): 8 círculos; o tempo atual em `accent` com o número em `on/accent`, os outros contorno `outline` com número `text_tertiary`. Atualiza ~25×/s enquanto toca.
- **Ações** (IconButton 48dp, `bg/icon-button`): frase anterior (`ic_skip_previous`), loop da frase (`ic_repeat`, `accent` quando ativo), próxima frase (`ic_skip_next`).
- Estados: analisando, sem estrutura (sem batida clara), pronta.

### 4.9 Feedback textual
- Toasts curtos para eventos assíncronos (resultado da detecção de BPM).
- Nenhum diálogo/modal nesta fase — estados são comunicados inline (linha de status); confirmações curtas em Snackbar.

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
- **GestureDetector**: `onDown` retorna `true`; `onSingleTapUp` = tocar (sem esperar duplo toque). Implementado via `RecyclerView.OnItemTouchListener` com `onInterceptTouchEvent` e `onTouchEvent` (não `SimpleOnItemTouchListener`).
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
- [ ] Dark-first respeitado e acento único (`#2EE6A8`) em toda ação? Cores extras só da paleta de dados (3.1.1)?
- [ ] Exatamente um FAB; ações principais na metade inferior?
- [ ] Herói visível no topo e maior número da tela?
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
| 1.5 | 2026-11-28 | Paleta de dados (3.1.1) para seções da música; acento único passa a valer para ação. Cartão Estrutura (4.13): seções, frases, contagem 1–8 e loop de frase. Cartão herói 30% menor (`display/hero` 45–53sp, altura mínima −30%); botões seguem com 48dp de toque. |
| 1.4 | 2026-11-27 | Lista de músicas em painel inferior arrastável (sai o menu hambúrguer); um toque toca; banner AdMob como último item da área que rola. |
| 1.3 | 2026-11-27 | Cartão Elementos (4.11) com "Separar" e "Limpar elementos"; tela de Configurações (4.12) para a pasta dos elementos separados. |
| 1.2 | 2026-11-27 | Redesign M3 Expressive: sliders com trilha grossa e alça em pílula (Material 1.13), herói centrado com −/+ de 48dp nas laterais, toolbar sem fundo (`bg/base`). |
