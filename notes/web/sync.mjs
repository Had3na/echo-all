import {lanFetch,pair,localAction} from './lan-client.mjs';
import * as store from './store.mjs';
let busy=false,remoteTag='',sessionKey='';
export function config() {try{return JSON.parse(localStorage.getItem('echo-sync')||'{}');}catch{return {};}}
export function serverURL(raw) {const u=new URL(raw);if(u.protocol!=='https:' && !(u.protocol==='http:'&&['localhost','127.0.0.1'].includes(u.hostname)))throw Error('Utilise une adresse HTTPS');if(u.username||u.password||u.pathname!=='/'||u.search||u.hash)throw Error('Indique uniquement l’adresse du serveur, sans chemin');return u.origin;}
export async function login(url,email,password) {
  const base=serverURL(url),res=await fetch(base+'/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password}),signal:AbortSignal.timeout(20000)});
  const data=await res.json();if(!res.ok)throw Error(data.error||'Connexion impossible');
  const binding=localStorage.getItem('echo-owner'),owner=base+'/'+data.userId;
  if(binding&&binding!==owner)throw Error('Ces notes sont liées à un autre compte. Utilise un autre profil de navigateur pour séparer les comptes.');
  localStorage.setItem('echo-owner',owner);localStorage.setItem('echo-sync',JSON.stringify({base,...data}));
}
async function request(path,options={}) {
  const c=config();const res=c.mode==='lan'?await lanFetch(c,path,options):await fetch(c.base+path,{...options,headers:{...options.headers,Authorization:'Bearer '+c.token},signal:AbortSignal.timeout(90000)});
  if(res.status===304||(options.method==='HEAD'&&res.status===404))return res;
  if(res.status===409)return {conflict:await res.json()};
  if(!res.ok){const data=await res.json().catch(()=>({}));throw Error(data.error||'Synchronisation interrompue');}
  return res;
}
export async function logout() {if(config().token)await request('/api/logout',{method:'POST'});localStorage.removeItem('echo-sync');}
export async function sync(report=()=>{},changed=()=>{},beforeChange=async()=>()=>{}) {
  if(busy)return;
  if(!config().token){report('Sur cet appareil · associe ton téléphone en Wi-Fi');return;}
  if(sessionKey!==config().base+config().token){sessionKey=config().base+config().token;remoteTag='';}
  busy=true;let conflicts=0;
  try {
    report(config().mode?.startsWith('lan')?'Synchronisation Wi-Fi…':'Synchronisation…');
    for(const entry of (await store.all()).filter(n=>n.dirty)) {
      const doc=entry.doc;
      if(doc.pdfId) {const file=await store.get(doc.pdfId,'files');if(!file)throw Error('PDF local manquant : sauvegarde conservée, envoi suspendu');if((await request('/api/files/'+doc.pdfId,{method:'HEAD'})).status===404)await request('/api/files/'+doc.pdfId,{method:'PUT',body:file.blob,headers:{'Content-Type':'application/pdf'}});}
      const result=await request('/api/notes/'+doc.id,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({baseRev:entry.rev,doc})});
      if(result.conflict) {
        const unlock=await beforeChange(doc.id);try{const copy=await store.conflict(doc.id,result.conflict);conflicts++;await changed({type:'conflict',id:doc.id,copy});}finally{unlock();}
      } else {const {rev}=await result.json();await store.mutate(doc.id,current=>store.afterUpload(current,entry,rev));}
    }
    const response=await request('/api/notes',{headers:remoteTag?{'If-None-Match':remoteTag}:{}});
    const remote=response.status===304?[]:await response.json();
    const nextTag=response.headers.get('ETag')||remoteTag;
    for(const entry of remote) {
      if(entry.doc.pdfId&&!await store.get(entry.doc.pdfId,'files')) {
        const blob=await (await request('/api/files/'+entry.doc.pdfId)).blob();
        if(await store.digest(blob)!==entry.doc.pdfId)throw Error('Vérification du PDF échouée');
        await store.put({id:entry.doc.pdfId,blob},'files');
      }
      let updated=false;const unlock=await beforeChange(entry.doc.id);
      try{await store.mutate(entry.doc.id,current=>{if(current?.dirty || (current&&current.rev>=entry.rev))return current;updated=true;return {id:entry.doc.id,doc:entry.doc,rev:entry.rev,version:0,dirty:false};});
      if(updated)await changed({type:'remote',id:entry.doc.id,doc:entry.doc});}finally{unlock();}
    }
    remoteTag=nextTag;
    const pending=(await store.all()).filter(n=>n.dirty).length;
    report(conflicts?'Copie en conflit conservée · compare les deux versions':pending?'Modifications locales en attente d’envoi':(config().mode?.startsWith('lan')?'Synchronisé en Wi-Fi · ':'Synchronisé · ')+new Date().toLocaleTimeString('fr-FR',{hour:'2-digit',minute:'2-digit'}));
  } catch(e) {report((navigator.onLine?'En attente · ':'Hors connexion · ')+e.message);}
  finally {busy=false;}
}

function bindLocal(data){const owner='lan:'+data.userId,previous=localStorage.getItem('echo-owner');if(previous&&previous!==owner)throw Error('Ces notes sont déjà liées à un autre espace. Sauvegarde-les avant de changer de PC ou de compte.');localStorage.setItem('echo-owner',owner);localStorage.setItem('echo-sync',JSON.stringify(data));remoteTag='';}
export async function connectLocal(){const data=await localAction('connect');bindLocal({...data,base:location.origin});}
export async function pairLocal(code){bindLocal(await pair(code));}
