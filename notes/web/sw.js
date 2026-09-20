importScripts('./precache.js');
const CACHE='echo-notes-014-1';
const SHELL=['./','./index.html','./style.css','./app.mjs','./store.mjs','./sync.mjs','./model.mjs','./lan-client.mjs','./local-ui.mjs','./echo-logo.png','./manifest.webmanifest','./icon.svg','./vendor/pdf.mjs','./vendor/pdf.worker.mjs','./vendor/pdf-lib.min.js'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(c=>c.addAll([...new Set([...SHELL,'./precache.js',...self.ECHO_PDF_ASSETS])]))));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('echo-notes-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(event.request.method!=='GET'||url.origin!==location.origin||url.pathname.startsWith('/api/'))return;event.respondWith(fetch(event.request).then(response=>{if(response.ok){const copy=response.clone();caches.open(CACHE).then(c=>c.put(event.request,copy));}return response;}).catch(()=>caches.match(event.request)));});
