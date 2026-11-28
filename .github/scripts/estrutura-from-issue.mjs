// Lê o corpo de uma issue do formulário estrutura.yml e grava structures/songs.json + structures/<id>/breakdown.json.
// Uso: ISSUE_BODY="..." node .github/scripts/estrutura-from-issue.mjs   → imprime o id gravado.
import fs from 'node:fs';
import path from 'node:path';

const body = process.env.ISSUE_BODY ?? '';
const block = (heading) => {
  const i = body.indexOf(`### ${heading}`);
  if (i < 0) throw new Error(`campo "${heading}" não encontrado`);
  const m = body.slice(i).match(/```(?:json)?\s*\n([\s\S]*?)\n```/);
  if (!m) throw new Error(`campo "${heading}" sem bloco JSON`);
  return JSON.parse(m[1]);
};

const musica = block('Música (songs.json)');
const breakdown = block('breakdown.json');
const ids = Object.keys(musica);
if (ids.length !== 1) throw new Error('songs.json deve ter exatamente uma música');
const id = ids[0].toLowerCase().replace(/[^a-z0-9]/g, '');
if (!id) throw new Error('id inválido');
const song = musica[ids[0]];
for (const k of ['title', 'artist', 'bpm']) if (song[k] === undefined || song[k] === '') throw new Error(`songs.json sem "${k}"`);
if (!(song.bpm > 0)) throw new Error('bpm inválido');
if (!Array.isArray(breakdown.sections) || breakdown.sections.length === 0) throw new Error('breakdown.json sem sections');
for (const s of breakdown.sections) {
  if (typeof s.title !== 'string' || !s.title) throw new Error('seção sem title');
  if (s.phrases === undefined) throw new Error(`seção "${s.title}" sem phrases`);
}

const root = 'structures';
const catalogPath = path.join(root, 'songs.json');
const catalog = fs.existsSync(catalogPath) ? JSON.parse(fs.readFileSync(catalogPath, 'utf8')) : {};
catalog[id] = { title: song.title, artist: song.artist, genre: song.genre ?? '', bpm: song.bpm, dateAdded: song.dateAdded ?? new Date().toISOString().slice(0, 10) + 'T00:00:00Z' };
const sorted = Object.fromEntries(Object.keys(catalog).sort().map((k) => [k, catalog[k]]));
fs.mkdirSync(path.join(root, id), { recursive: true });
fs.writeFileSync(catalogPath, JSON.stringify(sorted, null, 2) + '\n');
fs.writeFileSync(path.join(root, id, 'breakdown.json'), JSON.stringify(breakdown, null, 2) + '\n');
console.log(id);
