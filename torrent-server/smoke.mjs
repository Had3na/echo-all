import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import assert from 'node:assert/strict';
const d=path.join(process.env.LOCALAPPDATA,'Echo-All','TorrentServer');
const c=JSON.parse(fs.readFileSync(path.join(d,'config.json')));
const ca=fs.readFileSync(path.join(d,'cert.pem'));
function api(route,body,auth=true,headers={}) {
 return new Promise((resolve,reject)=>{
  const req=https.request(`https://${c.bind}:${c.port}${route}`,{ca,method:body?'POST':'GET',headers:{...(auth?{Authorization:'Bearer '+c.token}:{}),...headers}},res=>{
   const a=[];res.on('data',x=>a.push(x));res.on('end',()=>{const raw=Buffer.concat(a);let json;try{json=JSON.parse(raw)}catch{}resolve({status:res.statusCode,json,bytes:raw.length})});
  });req.setTimeout(60000,()=>req.destroy(Error('timeout')));req.on('error',reject);if(body)req.write(JSON.stringify(body));req.end();
 });
}
assert.equal((await api('/health',null,false)).status,401);
assert.equal((await api('/health')).status,200);
const search=await api('/search?q=Sintel');assert.equal(search.status,200);
const hit=search.json.hits.find(x=>x.source.startsWith('WebTorrent'));assert.ok(hit);
let jobs=(await api('/jobs')).json.jobs;
if(!jobs.some(j=>j.hash==='08ada5a7a6183aae1e09d831df6748d566095a10')) assert.equal((await api('/download',{id:hit.id})).status,200);
let job;
for(let i=0;i<60;i++){
 jobs=(await api('/jobs')).json.jobs;job=jobs.find(j=>j.hash==='08ada5a7a6183aae1e09d831df6748d566095a10');if(job?.progress===1)break;
 await new Promise(r=>setTimeout(r,1000));
}
assert.equal(job?.progress,1,'Sintel must finish');
const files=(await api(`/jobs/${job.hash}/files`)).json.files;assert.ok(files.length);
const route=`/stream/${job.hash}/${files[0].index}`;
assert.equal((await api(route,null,false,{Range:'bytes=0-1023'})).status,401);
const stream=await api(route,null,true,{Range:'bytes=0-1023'});assert.equal(stream.status,206);assert.equal(stream.bytes,1024);
assert.equal((await api(route,null,true,{Range:'bytes=999999999999-'})).status,416);
assert.equal((await api('/stream/'+ '0'.repeat(40)+'/0')).status,400);
fs.writeFileSync(path.join(d,'pairing.json'),JSON.stringify({address:`https://${c.bind}:${c.port}`,token:c.token,certificate:ca.toString()},null,2));
console.log('PASS: HTTPS authentication, search, Sintel complete, video range, invalid range, unknown torrent.');
