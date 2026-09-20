const enc=new TextEncoder(),dec=new TextDecoder();
const to64=bytes=>{let s='';for(let i=0;i<bytes.length;i+=8192)s+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(s);};
const from64=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
const fromURL=s=>from64(s.replace(/-/g,'+').replace(/_/g,'/')+'='.repeat((4-s.length%4)%4));
export function isPrivate(ip){const a=ip.split('.').map(Number);return /^\d{1,3}(\.\d{1,3}){3}$/.test(ip)&&a.every(n=>n>=0&&n<=255)&&(a[0]===10||(a[0]===192&&a[1]===168)||(a[0]===172&&a[1]>=16&&a[1]<=31));}
export function parseCode(code){const hex=code.trim().toUpperCase().replace(/^ECHO-/,'').replace(/[\s-]/g,'');if(!/^[0-9A-F]{40}$/.test(hex))throw Error('Copie le code ECHO complet affiché sur le PC');const bytes=Uint8Array.from(hex.match(/../g),s=>parseInt(s,16)),address=[...bytes.subarray(0,4)].join('.');if(!isPrivate(address))throw Error('Le code doit désigner une adresse du réseau local');return {address,key:bytes.subarray(4)};}
async function nativePost(address,packet){
 if(!isPrivate(address))throw Error('Adresse locale invalide');
 if(window.EchoNotes?.lanRequest){
   const id=crypto.randomUUID();window.echoLanPending??=new Map();
   window.echoLanResult=(id,result)=>{const pending=window.echoLanPending.get(id);if(!pending)return;window.echoLanPending.delete(id);clearTimeout(pending.timer);result.error?pending.reject(Error(result.error)):pending.resolve(result);};
   const result=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{window.echoLanPending.delete(id);reject(Error('PC inaccessible. Vérifie le Wi-Fi et laisse Notes ouvert sur le PC.'));},100000);window.echoLanPending.set(id,{resolve,reject,timer});window.EchoNotes.lanRequest(id,address,JSON.stringify(packet));});
   if(result.status!==200)throw Error('Association expirée ou refusée. Affiche un nouveau code sur le PC.');return JSON.parse(result.body);
 }
 // Windows talks to its own local companion, which forwards only encrypted packets to the LAN.
 const res=await fetch('/api/local/relay',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({address,packet}),signal:AbortSignal.timeout(95000)});
 const data=await res.json();if(!res.ok)throw Error(data.error||'PC inaccessible');return data;
}
async function exchange(address,keyBytes,kid,message){
 const key=await crypto.subtle.importKey('raw',keyBytes,'AES-GCM',false,['encrypt','decrypt']),iv=crypto.getRandomValues(new Uint8Array(12)),id=crypto.randomUUID();
 const params={name:'AES-GCM',iv,additionalData:enc.encode('echo-lan:v1:request:'+kid)};
 const data=await crypto.subtle.encrypt(params,key,enc.encode(JSON.stringify({...message,id,time:Date.now()})));
 const packet=await nativePost(address,{kid,iv:to64(iv),data:to64(new Uint8Array(data))});
 if(packet.kid!==kid)throw Error('Réponse d’un appareil inattendu');
 const plain=await crypto.subtle.decrypt({name:'AES-GCM',iv:from64(packet.iv),additionalData:enc.encode('echo-lan:v1:response:'+kid)},key,from64(packet.data));
 const output=JSON.parse(dec.decode(plain));if(output.id!==id)throw Error('Réponse locale non reconnue');return output;
}
export async function pair(code){const {address,key}=parseCode(code),kid=[...new Uint8Array(await crypto.subtle.digest('SHA-256',key))].map(x=>x.toString(16).padStart(2,'0')).join('').slice(0,24);const result=await exchange(address,key,kid,{action:'pair',name:window.EchoNotes?'Téléphone Echo-All':'Appareil Echo-All'});return {...result.config,address,base:'lan:'+result.config.userId};}
export async function lanFetch(config,path,options={}){
 const bytes=options.body instanceof Blob?new Uint8Array(await options.body.arrayBuffer()):enc.encode(options.body||'');
 const result=await exchange(config.address,fromURL(config.token),config.deviceId,{method:options.method||'GET',path,headers:options.headers||{},body:to64(bytes)});
 return new Response([204,304].includes(result.status)?null:from64(result.body),{status:result.status,headers:result.headers});
}
export async function localAction(action,body={}){const res=await fetch('/api/local/'+action,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(10000)});const data=await res.json();if(!res.ok)throw Error(data.error||'Action locale indisponible');return data;}
