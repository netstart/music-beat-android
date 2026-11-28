# Catálogo de estruturas

Estruturas de músicas feitas à mão no BeatTrack (**Estrutura manual**), no mesmo formato do emusicality
(ver [docs/EMUSICALITY_FORMATO.md](../docs/EMUSICALITY_FORMATO.md)):

- `songs.json` — catálogo: `id → { title, artist, genre, bpm, dateAdded }`
- `<id>/breakdown.json` — `trim`, `lag`, `beatsPerMeasure`, `tracks` e `sections` (frases)

`trim` é o segundo do primeiro "1" no arquivo de áudio usado; outra versão/master da música pode precisar de outro valor.

**Como contribuir:** no app, *Estrutura manual → Enviar para o GitHub*. Isso abre uma issue pública já preenchida
(formulário `.github/ISSUE_TEMPLATE/estrutura.yml`). Depois de revisada, o rótulo `estrutura-aprovada` faz o
workflow `estrutura.yml` abrir um pull request com os arquivos aqui.
