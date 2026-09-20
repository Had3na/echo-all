import {request as httpRequest} from 'node:http';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {createNotesServer} from '../server.mjs';
import {pack,unpack,privateAddress} from '../lan.mjs';

test('LAN: pairing, authenticated encryption, replay rejection, notes/PDF and revocation',{timeout:20000},async()=>{
 const dir=mkdtempSync(join(tmpdir(),'echo-lan-test-')),app=createNotesServer({dataDir:dir,lanEnabled:true});await new Promise(ok=>app.server.listen(0,'127.0.0.1',ok));const url='http://127.0.0.1:'+app.server.address().port;
 const post=(path,value,headers={})=>fetch(url+path,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(value)});
 try{
  assert.equal((await post('/api/local/connect',{}, {Origin:'https://evil.example'})).status,403);
  const wrongHost=await new Promise((ok,no)=>{const req=httpRequest(url+'/api/local/connect',{method:'POST',headers:{Host:'untrusted.example','Content-Type':'application/json'}},res=>{res.resume();ok(res.statusCode);});req.on('error',no);req.end('{}');});assert.equal(wrongHost,403);
  const pc=await(await post('/api/local/connect',{})).json();assert.ok(pc.token);assert.equal(pc.mode,'lan-pc');
  const pair=await(await post('/api/local/pair',{})).json();assert.ok(pair.code,'Test requires a private network interface');
  const raw=Buffer.from(pair.code.replace(/^ECHO-/,'').replaceAll('-',''),'hex'),key=raw.subarray(4),kid=createHash('sha256').update(key).digest('hex').slice(0,24);
  const id=randomUUID(),request=pack(key,kid,{id,time:Date.now(),action:'pair',name:'Phone fixture'},'request');
  const response=await post('/lan',request);assert.equal(response.status,200);const payload=unpack(key,await response.json(),'response');assert.equal(payload.id,id);assert.equal(payload.config.userId,pc.userId);const config=payload.config;
  assert.equal((await post('/lan',request)).status,403,'Pairing code is single-use');
  async function call(path,method='GET',body=''){
    const id=randomUUID(),packet=pack(Buffer.from(config.token,'base64url'),config.deviceId,{id,time:Date.now(),path,method,headers:{'Content-Type':'application/json'},body:Buffer.from(body).toString('base64')},'request');
    const response=await post('/lan',packet),output=await response.json();return {packet,status:response.status,value:response.status===200?unpack(Buffer.from(config.token,'base64url'),output,'response'):output};
  }
  const doc={id:'local-note',title:'Private course',folder:'Maths',text:'Only paired devices',kind:'text',pages:[[]],checks:[],pdfId:null,deleted:false};
  const saved=await call('/api/notes/local-note','PUT',JSON.stringify({baseRev:0,doc}));assert.equal(saved.value.status,200);assert.equal((await post('/lan',saved.packet)).status,403,'Replay rejected');
  const loaded=await call('/api/notes');assert.equal(JSON.parse(Buffer.from(loaded.value.body,'base64'))[0].doc.text,doc.text);
  const bytes=Buffer.from('%PDF-1.7\nLAN fixture'),fileId=createHash('sha256').update(bytes).digest('hex');
  assert.equal((await call('/api/files/'+fileId,'PUT',bytes)).value.status,200);assert.deepEqual(Buffer.from((await call('/api/files/'+fileId)).value.body,'base64'),bytes);
  const altered={...loaded.packet,data:'AAAA'+loaded.packet.data.slice(4)};assert.equal((await post('/lan',altered)).status,403,'Modified ciphertext rejected');
  await post('/api/local/revoke',{});assert.equal((await call('/api/notes')).status,403,'Revoked phone cannot read');
 }finally{await new Promise(ok=>app.server.close(ok));assert.ok(dir.startsWith(tmpdir()));rmSync(dir,{recursive:true,force:true});}
});
test('LAN target allow-list excludes public, malformed and loopback addresses',()=>{for(const ip of ['192.168.1.18','10.0.0.2','172.16.0.1','172.31.255.254'])assert.equal(privateAddress(ip),true);for(const ip of ['127.0.0.1','8.8.8.8','172.32.0.1','192.168.1.999','example.com','100.69.75.89','::1'])assert.equal(privateAddress(ip),false);});
