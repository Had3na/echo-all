import {networkInterfaces,hostname} from 'node:os';
import {randomBytes,randomUUID,createHash,createCipheriv,createDecipheriv} from 'node:crypto';
const hash=x=>createHash('sha256').update(x).digest('hex');
export function privateAddress(ip){const parts=ip.split('.').map(Number);return /^\d{1,3}(\.\d{1,3}){3}$/.test(ip)&&parts.every(n=>n>=0&&n<=255)&&(parts[0]===10||(parts[0]===192&&parts[1]===168)||(parts[0]===172&&parts[1]>=16&&parts[1]<=31));}
export function pack(key,kid,value,direction='response'){
 const iv=randomBytes(12),cipher=createCipheriv('aes-'+key.length*8+'-gcm',key,iv);cipher.setAAD(Buffer.from('echo-lan:v1:'+direction+':'+kid));
 return {kid,iv:iv.toString('base64'),data:Buffer.concat([cipher.update(JSON.stringify(value)),cipher.final(),cipher.getAuthTag()]).toString('base64')};
}
export function unpack(key,packet,direction='request'){
 const iv=Buffer.from(packet.iv||'','base64'),data=Buffer.from(packet.data||'','base64');if(iv.length!==12||data.length<16)throw Error('Packet invalide');
 const decipher=createDecipheriv('aes-'+key.length*8+'-gcm',key,iv);decipher.setAAD(Buffer.from('echo-lan:v1:'+direction+':'+packet.kid));decipher.setAuthTag(data.subarray(-16));
 return JSON.parse(Buffer.concat([decipher.update(data.subarray(0,-16)),decipher.final()]).toString());
}
export function createLan(db){
 db.exec(`CREATE TABLE IF NOT EXISTS local_config(k TEXT PRIMARY KEY,v TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS lan_devices(id TEXT PRIMARY KEY,key TEXT NOT NULL,name TEXT NOT NULL,created INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS lan_nonces(k TEXT NOT NULL,id TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(k,id));`);
 const setting=(k,generate)=>{let row=db.prepare('SELECT v FROM local_config WHERE k=?').get(k);if(!row){row={v:generate()};db.prepare('INSERT INTO local_config VALUES(?,?)').run(k,row.v);}return row.v;};
 const user=setting('workspace',randomUUID),pcToken=setting('pcToken',()=>randomBytes(32).toString('base64url'));
 db.prepare('INSERT OR IGNORE INTO users VALUES(?,?,?,?)').run(user,'local-'+user,'','');
 let pairing=null;const failed=new Map();
 const addresses=()=>Object.entries(networkInterfaces()).filter(([name])=>!/virtual|vEthernet|VPN|Tailscale|Nord|docker|WSL/i.test(name)).flatMap(([,rows])=>rows.filter(i=>i.family==='IPv4'&&!i.internal&&privateAddress(i.address)).map(i=>i.address));
 function session(token){db.prepare('INSERT OR REPLACE INTO sessions VALUES(?,?,?)').run(hash(token),user,Date.now()+365*86400000);}
 function send(res,status,value){const data=Buffer.from(JSON.stringify(value));res.writeHead(status,{'Content-Type':'application/json','Content-Length':data.length,'Cache-Control':'no-store'});res.end(data);}
 return async function handle(req,res,url,readBody,port){
   if(!url.pathname.startsWith('/api/local/')&&url.pathname!=='/lan')return false;
   if(url.pathname.startsWith('/api/local/')){
     const ip=req.socket.remoteAddress?.replace('::ffff:',''),origin=req.headers.origin;
     const hosts=['localhost:'+port,'127.0.0.1:'+port];
     if(!['127.0.0.1','::1'].includes(ip)||!hosts.includes(req.headers.host)||(origin&&!hosts.some(h=>origin==='http://'+h))||req.headers['sec-fetch-site']==='cross-site'){send(res,403,{error:'Action réservée à ce PC'});return true;}
     if(req.method==='GET'&&url.pathname==='/api/local/info'){send(res,200,{local:true,name:hostname(),addresses:addresses(),version:'0.14.0'});return true;}
     if(req.method!=='POST'||!req.headers['content-type']?.startsWith('application/json')){send(res,405,{error:'Action invalide'});return true;}
     const input=JSON.parse((await readBody(req,url.pathname==='/api/local/relay'?48*1024*1024:4096)).toString()||'{}');
     if(url.pathname==='/api/local/relay'){
       if(!privateAddress(String(input.address))){send(res,400,{error:'Adresse locale invalide'});return true;}
       const response=await fetch('http://'+input.address+':4319/lan',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input.packet),signal:AbortSignal.timeout(90000)});
       send(res,response.status,await response.json());return true;
     }
     if(url.pathname==='/api/local/connect'){session(pcToken);send(res,200,{mode:'lan-pc',token:pcToken,userId:user,email:'Ce PC',name:hostname()});return true;}
     if(url.pathname==='/api/local/pair'){
       const ip=addresses().includes(input.address)?input.address:addresses()[0];if(!ip){send(res,400,{error:'Aucune connexion locale détectée. Connecte le PC au réseau de la maison.'});return true;}
       const key=randomBytes(16),kid=hash(key).slice(0,24);pairing={key,kid,expires:Date.now()+300000};
       const code='ECHO-'+Buffer.concat([Buffer.from(ip.split('.').map(Number)),key]).toString('hex').toUpperCase().match(/.{1,8}/g).join('-');
       send(res,200,{code,expiresAt:pairing.expires,address:ip,name:hostname()});return true;
     }
     if(url.pathname==='/api/local/revoke'){
       for(const device of db.prepare('SELECT key FROM lan_devices').all())db.prepare('DELETE FROM sessions WHERE token=?').run(hash(device.key));
       db.exec('DELETE FROM lan_devices');pairing=null;send(res,200,{ok:true});return true;
     }
     send(res,404,{error:'Action inconnue'});return true;
   }
   const ip=req.socket.remoteAddress?.replace('::ffff:','');
   if(req.method!=='POST'||(!privateAddress(ip||'')&&!['127.0.0.1','::1'].includes(ip))){send(res,403,{error:'Réseau local requis'});return true;}
   let packet,key;
   try{
     const now=Date.now();for(const[k,v]of failed)if(now-v.time>60000)failed.delete(k);
     if((failed.get(ip)?.count||0)>=12){send(res,429,{error:'Association temporairement bloquée. Réessaie dans une minute.'});return true;}
     packet=JSON.parse((await readBody(req,48*1024*1024)).toString());
     const isPair=pairing?.kid===packet.kid&&pairing.expires>now;
     const device=isPair?null:db.prepare('SELECT * FROM lan_devices WHERE id=?').get(String(packet.kid));
     key=isPair?pairing.key:device?Buffer.from(device.key,'base64url'):null;if(!key)throw Error('Association absente');
     const message=unpack(key,packet);
     if(typeof message.id!=='string'||message.id.length>80||!Number.isFinite(message.time)||Math.abs(now-message.time)>600000)throw Error('Requête expirée');
     db.prepare('DELETE FROM lan_nonces WHERE created<?').run(now-1200000);
     if(!db.prepare('INSERT OR IGNORE INTO lan_nonces VALUES(?,?,?)').run(packet.kid,message.id,now).changes)throw Error('Requête rejouée');
     if(isPair){
       if(message.action!=='pair')throw Error('Action invalide');pairing=null;
       const token=randomBytes(32).toString('base64url'),id=randomUUID(),name=String(message.name||'Téléphone').slice(0,80);
       db.prepare('INSERT INTO lan_devices VALUES(?,?,?,?)').run(id,token,name,now);session(token);
       send(res,200,pack(key,packet.kid,{id:message.id,config:{mode:'lan',token,userId:user,deviceId:id,name:hostname()}}));return true;
     }
     if(!['GET','HEAD','PUT','POST'].includes(message.method)||!/^\/api\/(notes(?:\/[A-Za-z0-9_-]{1,80})?|files\/[a-f0-9]{64}|logout)$/.test(message.path))throw Error('Route invalide');
     const headers={Authorization:'Bearer '+device.key};
     for(const name of ['Content-Type','If-None-Match'])if(typeof message.headers?.[name]==='string')headers[name]=message.headers[name];
     const response=await fetch('http://127.0.0.1:'+port+message.path,{method:message.method,headers,body:['PUT','POST'].includes(message.method)?Buffer.from(message.body||'','base64'):undefined,signal:AbortSignal.timeout(90000)});
     const output={id:message.id,status:response.status,headers:{'Content-Type':response.headers.get('content-type')||'application/octet-stream','ETag':response.headers.get('etag')||''},body:Buffer.from(await response.arrayBuffer()).toString('base64')};
     send(res,200,pack(key,packet.kid,output));return true;
   }catch(e){const entry=failed.get(ip)||{time:Date.now(),count:0};entry.count++;failed.set(ip,entry);send(res,403,{error:'Association invalide ou expirée. Génère un nouveau code sur le PC.'});return true;}
 };
}
