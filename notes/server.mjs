import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { randomBytes, scryptSync, timingSafeEqual, createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, existsSync } from 'node:fs';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

import {createLan} from './lan.mjs';
const root = dirname(fileURLToPath(import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const MAX_DOC = 4 * 1024 * 1024, MAX_PDF = 25 * 1024 * 1024, QUOTA = 250 * 1024 * 1024;
const idPattern = /^[a-zA-Z0-9_-]{1,80}$/;
export { validateNote } from './web/model.mjs';
import { validateNote } from './web/model.mjs';
export function createNotesServer({ dataDir = process.env.ECHO_DATA || resolve(root, 'data'), origin = process.env.ECHO_ORIGIN || '', allowLocal = true, lanEnabled = process.env.ECHO_LAN === '1' } = {}) {
  mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(resolve(dataDir, 'notes.sqlite'));
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,salt TEXT NOT NULL,password TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,user TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS notes(user TEXT NOT NULL REFERENCES users(id),id TEXT NOT NULL,rev INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(user,id));
    CREATE TABLE IF NOT EXISTS files(user TEXT NOT NULL REFERENCES users(id),id TEXT NOT NULL,body BLOB NOT NULL,PRIMARY KEY(user,id));`);
  const rate = new Map();
  const lan = lanEnabled ? createLan(db) : null;
  function createUser(email, password) {
    email = email.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || password.length < 12 || password.length > 256) throw new Error('Adresse e-mail valide et mot de passe de 12 à 256 caractères requis');
    const salt = randomBytes(16).toString('hex');
    db.prepare('INSERT INTO users VALUES(?,?,?,?)').run(randomUUID(), email, salt, scryptSync(password, salt, 64).toString('hex'));
  }
  function json(res, status, body) { res.writeHead(status, { 'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store' }); res.end(JSON.stringify(body)); }
  async function body(req, max) {
    if (Number(req.headers['content-length']) > max) throw Object.assign(new Error('Fichier trop volumineux'), { status:413 });
    const chunks=[]; let size=0;
    for await (const chunk of req) { size += chunk.length; if(size>max) throw Object.assign(new Error('Fichier trop volumineux'), { status:413 }); chunks.push(chunk); }
    return Buffer.concat(chunks);
  }
  const server = createServer(async (req,res) => {
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    const requestOrigin = req.headers.origin;
    const allowed = new Set(['https://notes.echo-all.local', ...(origin ? [origin] : [])]);
    if (allowLocal) { allowed.add('http://localhost:4319'); allowed.add('http://127.0.0.1:4319'); }
    if (requestOrigin && allowed.has(requestOrigin)) {
      res.setHeader('Access-Control-Allow-Origin',requestOrigin); res.setHeader('Vary','Origin'); res.setHeader('Access-Control-Expose-Headers','ETag');
      res.setHeader('Access-Control-Allow-Headers','Authorization, Content-Type, If-None-Match'); res.setHeader('Access-Control-Allow-Methods','GET,HEAD,PUT,POST,OPTIONS');
    }
    try {
      const url = new URL(req.url, 'http://localhost');
      if(lan && await lan(req,res,url,body,server.address().port))return;
      if(lan && !['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress)){return json(res,403,{error:'Utilise l’association locale dans Echo-All.'});}
      if (url.pathname.startsWith('/api/')) {
        if(requestOrigin && !allowed.has(requestOrigin)) return json(res,403,{error:'Origine refusée'});
        if(req.method==='OPTIONS') { res.writeHead(204); return res.end(); }
        if(url.pathname==='/api/login' && req.method==='POST') {
          const ip=req.socket.remoteAddress; const now=Date.now();
          for(const [key,value] of rate) if(now-value.start>600000) rate.delete(key);
          const entry=rate.get(ip)||{start:now,count:0}; rate.set(ip,entry);
          if(++entry.count>10) return json(res,429,{error:'Trop de tentatives. Réessaie dans dix minutes.'});
          const input=JSON.parse((await body(req,4096)).toString());
          if(typeof input.email!=='string'||typeof input.password!=='string'||input.password.length>256) return json(res,400,{error:'Identifiants invalides'});
          const user=db.prepare('SELECT * FROM users WHERE email=?').get(input.email.trim().toLowerCase());
          const digest=scryptSync(input.password,user?.salt||'unknown-account',64);
          if(!user || !timingSafeEqual(digest,Buffer.from(user.password,'hex'))) return json(res,401,{error:'Adresse ou mot de passe incorrect'});
          const token=randomBytes(32).toString('base64url');
          db.prepare('DELETE FROM sessions WHERE expires<?').run(now);
          db.prepare('INSERT INTO sessions VALUES(?,?,?)').run(hash(token),user.id,now+30*86400000);
          return json(res,200,{token,userId:user.id,email:user.email});
        }
        const token=(req.headers.authorization||'').replace(/^Bearer /,'');
        const session=db.prepare('SELECT user FROM sessions WHERE token=? AND expires>?').get(hash(token),Date.now());
        if(!session) return json(res,401,{error:'Reconnecte-toi pour synchroniser'});
        const user=session.user;
        if(url.pathname==='/api/logout'&&req.method==='POST') { db.prepare('DELETE FROM sessions WHERE token=?').run(hash(token)); return json(res,200,{ok:true}); }
        if(url.pathname==='/api/notes'&&req.method==='GET') {
          const etag='"'+hash(JSON.stringify(db.prepare('SELECT id,rev FROM notes WHERE user=? ORDER BY id').all(user)))+'"';
          res.setHeader('ETag',etag);
          if(req.headers['if-none-match']===etag){res.writeHead(304);return res.end();}
          return json(res,200,db.prepare('SELECT body,rev FROM notes WHERE user=?').all(user).map(r=>({doc:JSON.parse(r.body),rev:r.rev})));
        }
        const noteMatch=url.pathname.match(/^\/api\/notes\/([a-zA-Z0-9_-]{1,80})$/);
        if(noteMatch&&req.method==='PUT') {
          const input=JSON.parse((await body(req,MAX_DOC+1024)).toString());
          const doc=validateNote(input.doc);
          if(doc.id!==noteMatch[1]||!Number.isSafeInteger(input.baseRev)||input.baseRev<0) return json(res,400,{error:'Révision invalide'});
          const old=db.prepare('SELECT rev,body FROM notes WHERE user=? AND id=?').get(user,doc.id);
          if((old?.rev||0)!==input.baseRev) return json(res,409,{rev:old?.rev||0,doc:old?JSON.parse(old.body):null});
          if(doc.pdfId&&!db.prepare('SELECT 1 FROM files WHERE user=? AND id=?').get(user,doc.pdfId)) return json(res,400,{error:'Le PDF doit être envoyé avant la note'});
          const text=JSON.stringify(doc);
          const used=db.prepare('SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) n FROM notes WHERE user=?').get(user).n;
          if(used+Buffer.byteLength(text)-(old?Buffer.byteLength(old.body):0)>QUOTA) return json(res,413,{error:'Limite de notes atteinte (250 Mo)'});
          const rev=(old?.rev||0)+1;
          db.prepare('INSERT INTO notes VALUES(?,?,?,?) ON CONFLICT(user,id) DO UPDATE SET rev=excluded.rev,body=excluded.body').run(user,doc.id,rev,text);
          return json(res,200,{rev});
        }
        const fileMatch=url.pathname.match(/^\/api\/files\/([a-f0-9]{64})$/);
        if(fileMatch) {
          const id=fileMatch[1];
          if(req.method==='GET'||req.method==='HEAD') {
            if(req.method==='HEAD'){const found=db.prepare('SELECT 1 FROM files WHERE user=? AND id=?').get(user,id);res.writeHead(found?200:404,{'Cache-Control':'no-store'});return res.end();}
            const file=db.prepare('SELECT body FROM files WHERE user=? AND id=?').get(user,id);
            if(!file)return json(res,404,{error:'PDF introuvable'});
            res.writeHead(200,{'Content-Type':'application/pdf','Cache-Control':'no-store'}); return res.end(Buffer.from(file.body));
          }
          if(req.method==='PUT') {
            const bytes=await body(req,MAX_PDF);
            if(hash(bytes)!==id||!bytes.subarray(0,1024).includes(Buffer.from('%PDF-')))return json(res,400,{error:'PDF invalide'});
            if(!db.prepare('SELECT 1 FROM files WHERE user=? AND id=?').get(user,id)) {
              const used=db.prepare('SELECT COALESCE(SUM(length(body)),0) n FROM files WHERE user=?').get(user).n;
              if(used+bytes.length>QUOTA)return json(res,413,{error:'Limite de PDF atteinte (250 Mo)'});
              db.prepare('INSERT INTO files VALUES(?,?,?)').run(user,id,bytes);
            }
            return json(res,200,{ok:true});
          }
        }
        return json(res,404,{error:'Route inconnue'});
      }
      if(!['GET','HEAD'].includes(req.method)){res.writeHead(405);return res.end();}
      const pathname=decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname);
      const web=resolve(root,'web'); const path=resolve(web,'.'+pathname);
      if(!path.startsWith(web+'/')&&!path.startsWith(web+'\\')) {res.writeHead(403);return res.end();}
      if(!existsSync(path)) {res.writeHead(404);return res.end();}
      const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml','.wasm':'application/wasm','.bcmap':'application/octet-stream','.ttf':'font/ttf'};
      res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' blob: data:; connect-src 'self' https:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
      res.writeHead(200,{'Content-Type':mime[extname(path)]||'application/octet-stream','Cache-Control':'no-cache'});
      res.end(req.method==='HEAD'?undefined:readFileSync(path));
    } catch(e) { if(!res.headersSent) json(res,e.status||400,{error:e.status===413?e.message:'Données invalides ou requête impossible'}); else res.end(); }
  });
  server.on('close',()=>db.close());
  server.headersTimeout=15000; server.requestTimeout=60000;
  return {server,createUser};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const app=createNotesServer({allowLocal:process.env.NODE_ENV!=='production'});
  if(process.argv[2]==='create-user') {
    const email=process.argv[3]; const password=randomBytes(18).toString('base64url');
    app.createUser(email||'',password);
    console.log('Compte créé : '+email+'\nMot de passe à conserver dans ton gestionnaire : '+password);
    process.exit(0);
  }
  const port=Number(process.env.PORT||4319), host=process.env.HOST||'127.0.0.1';
  app.server.listen(port,host,()=>console.log(`Echo-All Notes : http://${host}:${port}`));
}
