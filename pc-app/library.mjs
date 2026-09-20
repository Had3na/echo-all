// Pure helpers for the PC library: no filesystem, no network, so they can be tested directly.
import path from 'node:path';

export const AUDIO = ['mp3', 'm4a', 'flac', 'ogg', 'opus', 'wav', 'aac', 'wma'];
export const VIDEO = ['mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', 'ogv'];
export const IMAGE = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic'];

const MIME = {
  mp3: 'audio/mpeg', m4a: 'audio/mp4', flac: 'audio/flac', ogg: 'audio/ogg', opus: 'audio/ogg',
  wav: 'audio/wav', aac: 'audio/aac', wma: 'audio/x-ms-wma',
  mp4: 'video/mp4', m4v: 'video/mp4', mkv: 'video/x-matroska', webm: 'video/webm',
  avi: 'video/x-msvideo', mov: 'video/quicktime', ogv: 'video/ogg',
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif',
  webp: 'image/webp', heic: 'image/heic',
};

export const extensionOf = (name) => path.extname(name).slice(1).toLowerCase();

export function kindOf(name) {
  const ext = extensionOf(name);
  if (AUDIO.includes(ext)) return 'MUSIC';
  if (VIDEO.includes(ext)) return 'VIDEO';
  if (IMAGE.includes(ext)) return 'PHOTO';
  return null;
}

export const mimeOf = (name) => MIME[extensionOf(name)] || 'application/octet-stream';

/**
 * Best guess of artist and title from a file name, the same reading Echo-All does on the phone:
 * "03 - Artiste - Titre.mp3" and "Artiste_-_Titre" both give the same pair.
 */
export function guessTags(fileName) {
  let name = path.basename(fileName, path.extname(fileName))
    .replace(/_/g, ' ')
    .replace(/^\s*\d{1,3}\s*[-.)]\s*/, '')
    .replace(/\s+/g, ' ')
    .trim();
  const dash = name.indexOf(' - ');
  if (dash > 0) return { artist: name.slice(0, dash).trim(), title: name.slice(dash + 3).trim() };
  return { artist: '', title: name };
}

/** Stable id for a path, so a browser link survives a rescan. */
export const idFor = (relative) => Buffer.from(relative).toString('base64url');
export const pathFor = (id) => Buffer.from(id, 'base64url').toString('utf8');

/** Case-insensitive, accent-insensitive match over the fields a person would type. */
export function matches(entry, query) {
  const clean = (text) => (text || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const needle = clean(query).trim();
  if (!needle) return true;
  const hay = clean(`${entry.title} ${entry.artist} ${entry.folder}`);
  return needle.split(/\s+/).every((word) => hay.includes(word));
}

export function filterLibrary(entries, { q = '', kind = '' } = {}) {
  return entries.filter((e) => (!kind || e.kind === kind) && matches(e, q));
}

/**
 * Parses a Range header against a known size. Returns null when there is no range to honour, and
 * throws when the range cannot be satisfied, which the caller answers with 416.
 */
export function rangeFor(header, size) {
  if (!header) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) throw new Error('unsatisfiable');
  const [, rawStart, rawEnd] = match;
  if (rawStart === '' && rawEnd === '') throw new Error('unsatisfiable');
  let start;
  let end;
  if (rawStart === '') {
    // A suffix range: the last N bytes.
    const wanted = Number(rawEnd);
    if (wanted <= 0) throw new Error('unsatisfiable');
    start = Math.max(0, size - wanted);
    end = size - 1;
  } else {
    start = Number(rawStart);
    end = rawEnd === '' ? size - 1 : Math.min(Number(rawEnd), size - 1);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) throw new Error('unsatisfiable');
  return { start, end };
}

/**
 * Refuses any path that escapes its root once symlinks and "..' have been resolved. Every file the
 * server opens goes through here: a catalogue id arrives from the network and cannot be trusted.
 */
export function contained(root, candidate) {
  const base = path.resolve(root);
  const target = path.resolve(base, candidate);
  if (target !== base && !target.startsWith(base + path.sep)) throw new Error('hors du dossier autorisé');
  return target;
}
