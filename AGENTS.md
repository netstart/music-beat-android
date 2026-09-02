# Agentes — Regras Obrigatórias

## 1. Design System (Obrigatório)

**Antes de qualquer alteração na UI (layouts, cores, tipografia, componentes, motion, telas):**

1. Leia **`DESIGN_SYSTEM.md`** completo
2. Siga exclusivamente os tokens das Seções 3 (cores, tipo, espaçamento, raio, motion)
3. Respeite: **Dark-first**, **acento único `#2EE6A8`**, **um FAB por tela**, **ações na metade inferior**
4. Rode o **Checklist da Seção 8** antes de entregar

> Não invente valores. Se precisar de algo novo, registre primeiro no `DESIGN_SYSTEM.md`.

---

## 2. Stack & Convenções

- **Android nativo**: Kotlin + Views + ViewBinding + Material 3
- **Tema**: `Theme.Material3.Dark.NoActionBar` (cores em `colors.xml`, strings em `strings.xml`)
- **Player**: Media3 ExoPlayer + Sonic time-stretch (pitch preservado)
- **BPM detection**: Thread de fundo, `AudioDecoder` → `BpmDetector`
- **IDs**: `lowerCamelCase` semânticos (`bpmValue`, `trackSlider`)
- **Formatação**: `Locale.US` para valores técnicos

---

## 3. Qualidade Mínima (Non-negociável)

- Zero trabalho pesado na main thread
- BPM aplica em tempo real durante o arrasto do slider
- Estados completos: vazio, analisando, detectado, não-detectado, tocando, pausado, fim
- Alvos de toque ≥ 48×48dp, contraste verificado
- `contentDescription` em todo ícone interativo
- Textos só em `strings.xml` (PT-BR)
- Números que mudam em tempo real usam `fontFeatureSettings="tnum"`

---

## 4. Fluxo de Trabalho

```
1. Entender a tarefa
2. Ler DESIGN_SYSTEM.md (se toca UI)
3. Implementar seguindo tokens/receitas
4. Rodar checklist Seção 8
5. Testar: build + execução no device/emulador
6. Entregar
```

---

## 5. Arquivos-chave

| Arquivo | Função |
|---|---|
| `DESIGN_SYSTEM.md` | Fonte da verdade visual/comportamental |
| `bpm_app/app/src/main/res/values/colors.xml` | Tokens de cor (espelha Seção 3.1) |
| `bpm_app/app/src/main/res/values/styles.xml` | Tema + estilo do slider |
| `bpm_app/app/src/main/res/layout/activity_main.xml` | Layout principal |
| `bpm_app/app/src/main/kotlin/.../MainActivity.kt` | Lógica da tela |
| `bpm_app/app/src/main/res/values/strings.xml` | Todos os textos de usuário |