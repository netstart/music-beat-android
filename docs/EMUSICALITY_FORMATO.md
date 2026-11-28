# Como o emusicality configura as músicas e a estrutura

Engenharia reversa de <https://emusicality.co.uk> (Angular + Tone.js), feita em 2026-11-28 lendo os arquivos públicos do site e o `main.js` (classes `aK` = música, `cK` = trilha, `lK` = seção, `rR` = compasso, `BX` = player). Exemplo completo de uma música (Desnúdate), fora do git: `build-check/emusicality-exemplo/desnudate/`.

**Resumo:** nada é detectado. Cada música é **anotada à mão** num `breakdown.json`; o site só toca o áudio no BPM fixo e conta os tempos.

## 1. Arquivos por música

| Arquivo | O que é |
|---|---|
| `assets/songs/songs.json` | catálogo único: `id → { title, artist, genre, bpm, dateAdded, spotify? }` (11 músicas em 2026-11) |
| `assets/songs/<id>/breakdown.json` | a anotação (abaixo) |
| `assets/songs/<id>/track0.trk … trackN.trk` | um áudio por instrumento, **M4A** com extensão trocada (~3 MB cada); a ordem é a de `tracks` |
| `assets/songs/<id>/cover.jpg` | capa |

Músicas com `"spotify": true` no catálogo **não têm stems**: `tracks` vira uma string com o ID da faixa no Spotify, e o site toca pelo Spotify do usuário (login nas configurações), sincronizando com `lag`.

## 2. `breakdown.json`

```json
{
  "trim": 1.85,            // segundos cortados do início de TODOS os arquivos de áudio
  "lag": 0,                // segundos até o tempo 1 (usado no modo Spotify; padrão 0)
  "beatsPerMeasure": 8,    // tamanho do "compasso": a contagem de dança 1..8
  "tracks": [ ... ],       // instrumentos (ou o ID do Spotify)
  "sections": [ ... ]      // a estrutura
}
```

### `tracks`
Cada item é um texto (`"Vocals"`) ou `{ "title", "groups", "trim" }`:
- `groups`: texto ou lista (`"Percussion"`, `"Rhythm"`, `"Guitars"`, `"Vocals"`, `"Acapella"`, `"Instrumental"`…). O menu de instrumentos tem "— All —" + a união dos grupos; escolher um grupo **silencia quem não é dele**.
- `trim` da trilha: atraso (s) para aquela trilha começar (ex.: violões que entram depois).
- Cada trilha tem mudo individual.

### `sections` → frases → compassos → tempos
```json
{ "title": "Chorus", "structure": "Majao", "phrases": [4, 4] }
```
- `title`: nome livre (Intro, Verse, Pre Chorus, Chorus, Mambo, Bridge, Interlude, Breakdown, Outro, Montuno, Coda…). Títulos iguais = mesma cor.
- `structure` (opcional): rótulo de dança dos compassos (bachata: Derecho, Majao, Breakdown, Acapella, Dembow, Trap, Urban; salsa: Salsa, Oriza, Coro-Pregón, Piano Montuno, Horns/Moñas…). Aparece em "Rhythm / Framework". Sem ele: "—".
- `phrases`: lista de frases. Cada frase é:
  - **um número N** → N compassos de `beatsPerMeasure` tempos, com o `structure` da seção. `4` = 4 × 8 = 32 tempos (a frase padrão).
  - **uma lista** → cada item é **um compasso**:
    - número → compasso com esse tanto de tempos (`8` normal; `4` = meio compasso, **marcado como aviso**; `1` = só uma batida final);
    - texto → compasso de 8 tempos **e** troca o `structure` daqui em diante (`["Majao", 8, "Derecho", 8]` = 4 compassos: Majao, Majao, Derecho, Derecho);
    - objeto `{ "structure", "beats", "offset" }` → compasso sob medida; `offset` faz a contagem começar no meio (`{ "beats": 4, "offset": 4 }` = conta 5..8, uma anacruse); `{}` = compasso de 8 sem rótulo.
- Tudo vira um índice de tempo contínuo começando em 1, na ordem.

**Onde mora a "ponte que muda o 1":** um compasso de 4 tempos no fim de uma frase (`[8, 8, 8, 8, 4]`). Na Desnúdate há dois (Pre Chorus 1:50,77 e Mambo 2:36,92); depois deles o "1" anda meio compasso. Das 11 músicas, **7 têm pelo menos um compasso diferente de 8**, e quase todos são de **4 tempos** (meia oitava). Exceções: um de 2 tempos (Suegra) e um de 1 tempo (batida final, Eso Es Amor). Besito a Besito tem 9 meios compassos e Suegra, 10. Ou seja, em bachata a ponte de meia oitava é a regra, não a exceção.

## 3. Do arquivo para o som e para a tela

- **Tempo**: o relógio (Tone.Transport) roda no `bpm` do catálogo, **fixo**. Tempo `n` ↔ segundo `(n − 1) · 60 / bpm + lag`. Cada trilha começa em `trilha.trim` com o arquivo pulando `trim` segundos (é isso que alinha o tempo 1 ao zero).
- **Posição atual**: `tempo = arredonda((segundos − lag) / duração do tempo) + 1`; daí acha seção → frase → compasso → tempo dentro do compasso.
- **Song structure**: barra com um bloco por seção, largura ∝ tempos; legenda com os títulos únicos. **Cores** por título: intro ciano, verse laranja, pre chorus amarelo, chorus/montuno verde, mambo azul-oceano, bridge framboesa, outro/ending vermelho; títulos fora da lista pegam as cores que sobraram. Clique pula para a seção.
- **Section phrasing**: uma bolinha por frase da seção atual.
- **Beats / counts**: uma bolinha por tempo do compasso atual (8, ou 4 no meio compasso); o atual pulsa — **menos em Salsa**.
- **Transporte**: início, frase anterior, play/pause (com fade de colcheia), próxima frase, fim; **loop** da frase atual. Fim automático 8 tempos depois do último.

## 4. O que isso muda para o BeatTrack

| emusicality | BeatTrack hoje |
|---|---|
| tudo anotado à mão | detectado (grade, "1", pontes, seções) + toque no 1 para corrigir |
| `trim`/`lag` | `firstBeatMs` |
| BPM do catálogo | BPM detectado e refinado na música inteira |
| compasso de 4 numa frase | `Shift(tempo, +4)` (pulo de meia oitava) |
| `structure` (Derecho/Majao) | não existe (só dá à mão) |
| stems feitos em estúdio, 4–8 por música | 4 stems do HTDemucs no aparelho |
| cor por título | paleta de dados 3.1.1 por tipo de seção |

Um importador de `breakdown.json` é viável: cada compasso de 8 vira 8 tempos; cada compasso diferente de 8 vira um `Shift`; cada seção vira `Section`. Faltaria mapear títulos livres para os 6 tipos do app.
