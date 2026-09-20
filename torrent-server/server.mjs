import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
export function rangeFor(header,size){
 if(!header)return [0,size-1,false];
 const m=/^bytes=(\d*)-(\d*)$/.exec(header);if(!m||(!m[1]&&!m[2]))throw Error('range');
 let start=m[1]?Number(m[1]):Math.max(0,size-Number(m[2]));let end=m[1]?(m[2]?Number(m[2]):size-1):size-1;
 if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||start>=size||end<start)throw Error('range');
 return [start,Math.min(end,size-1),true];
}
export function contained(root,name){const target=path.resolve(root,name);if(!target.startsWith(path.resolve(root)+path.sep))throw Error('Chemin interdit');return target;}
const hashValid=h=>/^[a-f\d]{40}$/i.test(h);
const magnet=(hash,title)=>`magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(title)}`;
export async function start(dataDir){
 const config=JSON.parse(fs.readFileSync(path.join(dataDir,'config.json'),'utf8'));
 fs.mkdirSync(config.downloadRoot,{recursive:true});
 const results=new Map();
 async function qb(method,body){const r=await fetch(config.qb+'/api/v2/'+method,{method:body?'POST':'GET',headers:{Referer:config.qb,...(body?{'Content-Type':'application/x-www-form-urlencoded'}:{})},body:body?new URLSearchParams(body):undefined,signal:AbortSignal.timeout(20000)});if(!r.ok)throw Error('qBittorrent indisponible');const t=await r.text();try{return JSON.parse(t)}catch{return t}}
 async function remote(url,max=8000000){for(let i=0;i<5;i++){const u=new URL(url);if(u.protocol!=='https:'||!(['archive.org','apibay.org','movies-api.accel.li','webtorrent.io'].includes(u.hostname)||u.hostname.endsWith('.archive.org')))throw Error('Source non autorisée');const r=await fetch(u,{redirect:'manual',signal:AbortSignal.timeout(25000)});if([301,302,303,307,308].includes(r.status)){url=new URL(r.headers.get('location'),u).href;continue}if(!r.ok)throw Error('Source indisponible');const chunks=[];let n=0;for await(const b of r.body){n+=b.length;if(n>max)throw Error('Réponse trop volumineuse');chunks.push(b)}return Buffer.concat(chunks)}throw Error('Redirection excessive')}
 async function search(q){if(q.length<2||q.length>200)throw Error('Titre de 2 à 200 caractères requis');const errors=[];const providers=[
 ['The Pirate Bay',async()=>{const a=JSON.parse(await remote('https://apibay.org/q.php?cat=200&q='+encodeURIComponent(q)));return a.filter(x=>hashValid(x.info_hash)&&Number(x.category)>=200&&Number(x.category)<300).map(x=>({title:x.name,source:'The Pirate Bay',magnet:magnet(x.info_hash,x.name),seeders:Number(x.seeders)||0,bytes:Number(x.size)||0}))}],
 ['YTS',async()=>{const a=JSON.parse(await remote('https://movies-api.accel.li/api/v2/list_movies.json?limit=50&query_term='+encodeURIComponent(q)));if(a.status!=='ok')throw Error();return (a.data.movies||[]).flatMap(m=>(m.torrents||[]).filter(t=>hashValid(t.hash)).map(t=>{const title=[m.title_long||m.title,t.quality,t.type,m.language].filter(Boolean).join(' ');return {title,source:'YTS',magnet:magnet(t.hash,title),seeders:Number(t.seeds)||0,bytes:Number(t.size_bytes)||0}}))}],
 ['Internet Archive',async()=>{const term=q.replaceAll('\\','\\\\').replaceAll('"','\\"');const query=`title:("${term}") AND mediatype:movies AND format:"Archive BitTorrent" AND -access-restricted-item:true`;const a=JSON.parse(await remote('https://archive.org/advancedsearch.php?output=json&rows=25&fl[]=identifier&fl[]=title&q='+encodeURIComponent(query)));return (a.response?.docs||[]).filter(m=>/^[\w.-]+$/.test(m.identifier)).map(m=>({title:m.title,source:'Internet Archive',archiveId:m.identifier,seeders:null,bytes:0}))}]];
 const hits=(await Promise.all(providers.map(async([name,fn])=>{try{return await fn()}catch{errors.push(name+' : indisponible');return []}}))).flat();
 if(/sintel/i.test(q))hits.unshift({title:'Sintel',source:'WebTorrent · film libre',url:'https://webtorrent.io/torrents/sintel.torrent',seeders:null,bytes:129302391});
 const now=Date.now();for(const [id,v]of results)if(now-v.at>3600000)results.delete(id);
 while(results.size>2000)results.delete(results.keys().next().value);
 return {hits:hits.slice(0,250).map(h=>{const id=crypto.randomUUID();results.set(id,{...h,at:now});return {...h,id,magnet:undefined,url:undefined,archiveId:undefined}}),errors};
 }
 async function body(req){let n=0;const a=[];for await(const b of req){n+=b.length;if(n>5000000)throw Error('Envoi trop volumineux');a.push(b)}return JSON.parse(Buffer.concat(a).toString()||'{}')}
 const jobs=()=>qb('torrents/info?category=echo-all');
 async function job(hash){if(!hashValid(hash))throw Error('Torrent invalide');const j=(await jobs()).find(j=>j.hash===hash);if(!j)throw Error('Torrent introuvable');return j}
 async function add(reference,bytes){const form=new FormData();if(bytes)form.set('torrents',new Blob([bytes]),'film.torrent');else form.set('urls',reference);form.set('savepath',config.downloadRoot);form.set('category','echo-all');form.set('stopped','false');form.set('ratioLimit','0');form.set('seedingTimeLimit','0');form.set('autoTMM','false');const r=await fetch(config.qb+'/api/v2/torrents/add',{method:'POST',headers:{Referer:config.qb},body:form,signal:AbortSignal.timeout(30000)});const response=await r.text();if(r.status===409)throw Error('Ce torrent est déjà présent ou a été refusé. Consulte les téléchargements du PC.');if(!r.ok)throw Error('Ajout refusé par qBittorrent');if(response.trim()!=='Ok.'){let result;try{result=JSON.parse(response)}catch{throw Error('Réponse inattendue de qBittorrent')}if(!result.success_count&&!result.pending_count)throw Error('Aucun torrent ajouté')}return {ok:true}}
 const server=https.createServer({key:fs.readFileSync(path.join(dataDir,'key.pem')),cert:fs.readFileSync(path.join(dataDir,'cert.pem'))},async(req,res)=>{
  const send=(status,data)=>{if(res.headersSent)return;const bytes=Buffer.from(JSON.stringify(data));res.writeHead(status,{'Content-Type':'application/json','Content-Length':bytes.length,'Cache-Control':'no-store'});res.end(bytes)};
  const supplied=Buffer.from(req.headers.authorization||''),expected=Buffer.from('Bearer '+config.token);
  if(supplied.length!==expected.length||!crypto.timingSafeEqual(supplied,expected)){send(401,{error:'Accès refusé'});return}
  try{const u=new URL(req.url,'https://local');const parts=u.pathname.split('/').filter(Boolean);
   if(req.method==='GET'&&u.pathname==='/health'){send(200,{ok:true,version:await qb('app/version')});return}
   if(req.method==='GET'&&u.pathname==='/search'){send(200,await search((u.searchParams.get('q')||'').trim()));return}
   if(req.method==='GET'&&u.pathname==='/jobs'){send(200,{jobs:await jobs()});return}
   if(req.method==='POST'&&u.pathname==='/download'){const b=await body(req);const hit=results.get(b.id);if(!hit||Date.now()-hit.at>3600000)throw Error('Relance la recherche pour actualiser ce résultat');if(hit.magnet)send(200,await add(hit.magnet));else{let url=hit.url;if(hit.archiveId){const m=JSON.parse(await remote('https://archive.org/metadata/'+hit.archiveId));const f=m.files?.find(f=>f.name.endsWith('.torrent'));if(!f)throw Error('Torrent absent');url='https://archive.org/download/'+hit.archiveId+'/'+encodeURIComponent(f.name)}send(200,await add(null,await remote(url,4000000)))}return}
   if(req.method==='POST'&&u.pathname==='/import'){const b=await body(req);if(b.magnet){if(!/^magnet:\?/.test(b.magnet)||!/[?&]xt=urn:btih:[a-f\d]{40}(&|$)/i.test(b.magnet))throw Error('Magnet invalide');send(200,await add(b.magnet))}else{const bytes=Buffer.from(b.torrent||'','base64');if(bytes.length<1||bytes.length>4000000||bytes[0]!==100)throw Error('Fichier torrent invalide');send(200,await add(null,bytes))}return}
   if(parts[0]==='jobs'&&parts.length===3){const j=await job(parts[1]);if(req.method==='POST'&&['pause','resume'].includes(parts[2])){await qb('torrents/'+(parts[2]==='pause'?'stop':'start'),{hashes:j.hash});send(200,{ok:true});return}if(req.method==='GET'&&parts[2]==='files'){const files=await qb('torrents/files?hash='+j.hash);send(200,{files:files.filter(f=>/\.(mp4|mkv|webm|avi|mov|m4v)$/i.test(f.name)&&f.progress===1).map(f=>({index:f.index,name:f.name,size:f.size}))});return}}
   if(req.method==='GET'&&parts[0]==='stream'&&parts.length===3){const j=await job(parts[1]);const f=(await qb('torrents/files?hash='+j.hash)).find(f=>String(f.index)===parts[2]&&f.progress===1&&/\.(mp4|mkv|webm|avi|mov|m4v)$/i.test(f.name));if(!f)throw Error('Vidéo pas encore terminée');const base=fs.realpathSync(config.downloadRoot);const file=fs.realpathSync(contained(base,f.name));contained(base,path.relative(base,file));const stat=fs.statSync(file);let range;try{range=rangeFor(req.headers.range,stat.size)}catch{res.writeHead(416,{'Content-Range':`bytes */${stat.size}`});res.end();return}const [s,e,partial]=range;res.writeHead(partial?206:200,{'Content-Type':/\.mp4$/i.test(file)?'video/mp4':'video/x-matroska','Accept-Ranges':'bytes','Content-Length':e-s+1,...(partial?{'Content-Range':`bytes ${s}-${e}/${stat.size}`}:{})});const stream=fs.createReadStream(file,{start:s,end:e});stream.on('error',()=>res.destroy());res.on('close',()=>stream.destroy());stream.pipe(res);return}
   send(404,{error:'Route inconnue'});
  }catch(e){send(400,{error:e.message||'Erreur du serveur'})}
 });server.requestTimeout=60000;server.headersTimeout=15000;
 await new Promise((resolve,reject)=>server.once('error',reject).listen(config.port,config.bind,resolve));
 console.log(`Echo-All PC prêt sur https://${config.bind}:${config.port}`);return server;
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))start(process.env.ECHO_SERVER_DATA||path.join(process.env.LOCALAPPDATA,'Echo-All','TorrentServer')).catch(e=>{console.error(e.message);process.exitCode=1});
