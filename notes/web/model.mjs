const MAX_DOC=4*1024*1024;
const idPattern=/^[a-zA-Z0-9_-]{1,80}$/;
export function validateNote(doc) {
  if (!doc || typeof doc !== 'object' || !idPattern.test(doc.id || '') || typeof doc.title !== 'string' || doc.title.length > 200 || typeof doc.folder !== 'string' || doc.folder.length > 100 || typeof doc.text !== 'string' || doc.text.length > 200000 || !['text','ink','check','pdf'].includes(doc.kind) || !Array.isArray(doc.pages) || doc.pages.length > 500 || !Array.isArray(doc.checks) || doc.checks.length > 2000 || typeof doc.deleted !== 'boolean') throw new Error('Note invalide');
  if (doc.pdfId != null && !/^[a-f0-9]{64}$/.test(doc.pdfId)) throw new Error('PDF invalide');
  if (doc.kind === 'pdf' && !doc.pdfId) throw new Error('PDF manquant');
  if (doc.checks.some(c => typeof c.id !== 'string' || typeof c.text !== 'string' || c.text.length > 2000 || typeof c.done !== 'boolean')) throw new Error('Liste invalide');
  for (const page of doc.pages) {
    if (!Array.isArray(page) || page.length > 20000) throw new Error('Page invalide');
    for (const s of page) {
      if (!['pen','highlighter'].includes(s.tool) || !/^#[a-f0-9]{6}$/i.test(s.color) || !Number.isFinite(s.width) || s.width < .1 || s.width > 100 || !Array.isArray(s.points) || !s.points.length || s.points.length > 100000) throw new Error('Trait invalide');
      for (const p of s.points) if (!Array.isArray(p) || p.length !== 3 || p.some(n => !Number.isFinite(n) || n < 0 || n > 1)) throw new Error('Coordonnée invalide');
    }
  }
  if (new TextEncoder().encode(JSON.stringify(doc)).length > MAX_DOC) throw new Error('Note trop volumineuse (4 Mo maximum)');
  return doc;
}
