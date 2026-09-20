import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { kindOf, mimeOf, guessTags, idFor, pathFor, filterLibrary, rangeFor, contained } from './library.mjs';

test('chaque extension tombe dans le bon rayon', () => {
  assert.equal(kindOf('a.MP3'), 'MUSIC');
  assert.equal(kindOf('a.mkv'), 'VIDEO');
  assert.equal(kindOf('a.HEIC'), 'PHOTO');
  assert.equal(kindOf('notes.txt'), null);
  assert.equal(kindOf('sans-extension'), null);
  assert.equal(mimeOf('film.mp4'), 'video/mp4');
  assert.equal(mimeOf('x.inconnu'), 'application/octet-stream');
});

test('le nom de fichier donne artiste et titre', () => {
  assert.deepEqual(guessTags('03 - Daft Punk - One More Time.mp3'), { artist: 'Daft Punk', title: 'One More Time' });
  assert.deepEqual(guessTags('Orelsan_-_Basique.flac'), { artist: 'Orelsan', title: 'Basique' });
  // Rien qui ressemble à un séparateur : tout est le titre, on n'invente pas d'artiste.
  assert.deepEqual(guessTags('12. Seul.mp3'), { artist: '', title: 'Seul' });
});

test('un identifiant survit à un aller-retour, y compris avec accents et espaces', () => {
  const original = 'Musique/Été 2024/Café crème.mp3';
  assert.equal(pathFor(idFor(original)), original);
  // base64url : rien qui casse une URL.
  assert.ok(!/[+/=]/.test(idFor(original)));
});

test('la recherche ignore la casse et les accents, et exige tous les mots', () => {
  const library = [
    { title: 'Été', artist: 'Café', folder: 'Musique', kind: 'MUSIC' },
    { title: 'Hiver', artist: 'Autre', folder: 'Musique', kind: 'MUSIC' },
    { title: 'Film', artist: '', folder: 'Vidéos', kind: 'VIDEO' },
  ];
  assert.equal(filterLibrary(library, { q: 'ete' }).length, 1);
  assert.equal(filterLibrary(library, { q: 'CAFE ete' }).length, 1);
  assert.equal(filterLibrary(library, { q: 'cafe hiver' }).length, 0);
  assert.equal(filterLibrary(library, { kind: 'VIDEO' }).length, 1);
  assert.equal(filterLibrary(library, {}).length, 3);
});

test('les requêtes Range couvrent le début, la fin et le suffixe', () => {
  assert.equal(rangeFor(undefined, 100), null);
  assert.deepEqual(rangeFor('bytes=0-49', 100), { start: 0, end: 49 });
  // Fin ouverte : jusqu'au dernier octet.
  assert.deepEqual(rangeFor('bytes=50-', 100), { start: 50, end: 99 });
  // Suffixe : les N derniers octets.
  assert.deepEqual(rangeFor('bytes=-20', 100), { start: 80, end: 99 });
  // Une fin au-delà du fichier est ramenée à sa taille, elle ne fait pas échouer la lecture.
  assert.deepEqual(rangeFor('bytes=90-500', 100), { start: 90, end: 99 });
  for (const bad of ['bytes=100-', 'bytes=50-10', 'bytes=-0', 'bytes=', 'octets=0-1', 'nimporte']) {
    assert.throws(() => rangeFor(bad, 100), undefined, bad);
  }
});

test('aucun chemin ne sort du dossier autorisé', () => {
  const root = path.resolve('/media');
  assert.equal(contained(root, 'Musique/a.mp3'), path.resolve(root, 'Musique/a.mp3'));
  assert.equal(contained(root, '.'), root);
  for (const bad of ['../secret', 'Musique/../../secret', path.resolve('/etc/passwd')]) {
    assert.throws(() => contained(root, bad), undefined, bad);
  }
  // Un dossier voisin dont le nom commence pareil ne doit pas passer pour l'intérieur.
  assert.throws(() => contained(root, path.resolve('/mediaXXX/a.mp3')));
});
