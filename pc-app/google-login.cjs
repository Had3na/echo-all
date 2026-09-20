const { createServer } = require('node:http');
const { randomBytes, timingSafeEqual } = require('node:crypto');
// Google authentication runs in the system browser, never in an embedded webview.
exports.googleLogin = (config, shell) => new Promise((resolve, reject) => {
  const nonce = randomBytes(32).toString('hex');
  let origin, done = false;
  const finish = (error, token) => {
    if (done) return; done = true; clearTimeout(timer); server.close();
    error ? reject(error) : resolve(token);
  };
  const server = createServer((req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const url = new URL(req.url, origin);
    if (req.headers.host !== new URL(origin).host || url.searchParams.get('state') !== nonce) { res.writeHead(403); return res.end(); }
    if (req.method === 'GET' && url.pathname === '/') {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      return res.end(`<!doctype html><html lang="fr"><meta charset="utf-8"><title>Connexion Echo-All</title>
<style>body{font:18px system-ui;max-width:600px;margin:80px auto;padding:24px;background:#0d0f12;color:#eee}button{font:inherit;padding:16px}</style>
<h1>Connexion à Echo-All</h1><p>Connecte-toi pour retrouver tes amis sur cet ordinateur.</p><button id="login">Continuer avec Google</button><p id="status"></p>
<script type="module">
import {initializeApp} from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js';
import {getAuth,GoogleAuthProvider,signInWithPopup,setPersistence,inMemoryPersistence,signOut} from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js';
const config=await (await fetch('/config?state=${nonce}')).json();
const auth=getAuth(initializeApp(config)); await setPersistence(auth,inMemoryPersistence);
document.getElementById('login').onclick=async()=>{
try {
const result=await signInWithPopup(auth,new GoogleAuthProvider());
const token=GoogleAuthProvider.credentialFromResult(result)?.idToken;
if(!token) throw Error('Google ne fournit pas de jeton.');
const response=await fetch('/complete?state=${nonce}',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token})});
if(!response.ok) throw Error('Connexion expirée. Réessaie dans Echo-All.');
await signOut(auth);
document.getElementById('status').textContent='Connexion terminée. Tu peux revenir dans Echo-All.';
document.getElementById('login').disabled=true;
} catch(error){document.getElementById('status').textContent=error.message;}
};
</script></html>`);
    }
    if (req.method === 'GET' && url.pathname === '/config') {
      res.setHeader('Content-Type','application/json'); return res.end(JSON.stringify(config));
    }
    if (req.method === 'POST' && url.pathname === '/complete' && req.headers.origin === origin) {
      let body = '';
      req.on('data', chunk => { body += chunk; if (body.length > 20000) req.destroy(); });
      req.on('end', () => {
        try {
          const { token } = JSON.parse(body);
          if (typeof token !== 'string' || token.length < 100 || token.length > 16000) throw Error('Jeton invalide');
          res.end('OK'); finish(null, token);
        } catch { res.writeHead(400); res.end(); }
      });
      return;
    }
    res.writeHead(404); res.end();
  });
  const timer = setTimeout(() => finish(new Error('Connexion expirée. Réessaie.')), 180000);
  server.on('error', error => finish(error));
  server.listen(0, '127.0.0.1', () => {
    origin = `http://127.0.0.1:${server.address().port}`;
    shell.openExternal(`${origin}/?state=${nonce}`).catch(error => finish(error));
  });
});
