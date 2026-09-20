const el = (id) => document.getElementById(id);
const list = el('list');
const count = el('count');
const player = el('player');
const surface = el('surface');

let kind = '';
let playing = null;

const clock = (bytes) => (bytes >= 1_073_741_824
  ? `${(bytes / 1_073_741_824).toFixed(1)} Go`
  : `${Math.round(bytes / 1_048_576)} Mo`);

async function load() {
  const url = new URL('/api/library', location.origin);
  if (el('q').value.trim()) url.searchParams.set('q', el('q').value.trim());
  if (kind) url.searchParams.set('kind', kind);
  let data;
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Le serveur a répondu ${response.status}`);
    data = await response.json();
  } catch (error) {
    count.textContent = `Bibliothèque injoignable : ${error.message}`;
    list.replaceChildren();
    return;
  }
  count.textContent = data.count === data.total
    ? `${data.total} médias`
    : `${data.count} sur ${data.total} médias`;
  // The server caps its answer; say so rather than let the list look complete when it is not.
  if (data.count > data.items.length) count.textContent += ` · ${data.items.length} affichés`;
  list.replaceChildren(...data.items.map(render));
  if (!data.items.length) {
    const empty = document.createElement('li');
    empty.className = 'empty';
    empty.textContent = 'Rien ici. Ajoute des dossiers dans data/config.json, puis Réanalyser.';
    list.replaceChildren(empty);
  }
}

function render(item) {
  const row = document.createElement('button');
  row.className = 'row';
  row.type = 'button';
  row.setAttribute('aria-current', String(item.id === playing));

  const left = document.createElement('span');
  const title = document.createElement('span');
  title.className = 'title';
  title.textContent = item.title;
  const sub = document.createElement('span');
  sub.className = 'sub';
  sub.textContent = [item.artist, item.folder].filter(Boolean).join(' · ');
  left.append(title);
  if (sub.textContent) { left.append(document.createElement('br'), sub); }

  const meta = document.createElement('span');
  meta.className = 'meta';
  meta.textContent = `${item.kind === 'VIDEO' ? 'Vidéo' : item.kind === 'PHOTO' ? 'Photo' : 'Audio'} · ${clock(item.bytes)}`;

  row.append(left, meta);
  // The row hands itself to play(): relying on document.activeElement missed the mark whenever
  // focus had moved elsewhere, and the list then showed nothing as playing.
  row.addEventListener('click', () => play(item, row));

  const entry = document.createElement('li');
  entry.append(row);
  return entry;
}

function play(item, row) {
  if (item.kind === 'PHOTO') { window.open(`/api/file/${item.id}`, '_blank', 'noopener'); return; }
  playing = item.id;
  const media = document.createElement(item.kind === 'VIDEO' ? 'video' : 'audio');
  media.src = `/api/file/${item.id}`;
  media.controls = true;
  media.autoplay = true;
  // Replacing rather than reusing the element avoids the previous track's buffer being kept.
  surface.replaceChildren(media);
  el('now').textContent = [item.artist, item.title].filter(Boolean).join(' — ');
  player.hidden = false;
  for (const other of list.querySelectorAll('.row')) other.setAttribute('aria-current', 'false');
  row?.setAttribute('aria-current', 'true');
}

function pick(chosen) {
  kind = chosen;
  for (const [id, value] of [['all', ''], ['music', 'MUSIC'], ['video', 'VIDEO']]) {
    el(id).setAttribute('aria-pressed', String(value === chosen));
  }
  load();
}

let typing;
el('q').addEventListener('input', () => { clearTimeout(typing); typing = setTimeout(load, 200); });
el('all').addEventListener('click', () => pick(''));
el('music').addEventListener('click', () => pick('MUSIC'));
el('video').addEventListener('click', () => pick('VIDEO'));
el('rescan').addEventListener('click', async () => {
  el('rescan').disabled = true;
  try { await fetch('/api/rescan', { method: 'POST' }); await load(); }
  finally { el('rescan').disabled = false; }
});

load();
