import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from '@playwright/test';
import {createNotesServer} from '../server.mjs';

test('accountless PC and Android bridge: pair, sync, theme and reconnect',{timeout:90000},async()=>{
 const dir=mkdtempSync(join(tmpdir(),'echo-lan-ui-')),app=createNotesServer({dataDir:dir,lanEnabled:true,origin:'http://127.0.0.1:4323'});await new Promise(ok=>app.server.listen(4323,'127.0.0.1',ok));
 const browser=await chromium.launch({channel:'msedge',headless:true});const pc=await browser.newContext({viewport:{width:1440,height:1000}}),phone=await browser.newContext({viewport:{width:390,height:844}});
 const errors=[];
 try{
  await phone.exposeBinding('nativeLan',async({page},id,address,packet)=>{assert.match(address,/^(192\.168\.|10\.|172\.)/);const response=await fetch('http://127.0.0.1:4323/lan',{method:'POST',headers:{'Content-Type':'application/json'},body:packet});const result={status:response.status,body:await response.text()};await page.evaluate(({id,result})=>window.echoLanResult(id,result),{id,result});});
  await phone.addInitScript(()=>{window.EchoNotes={lanRequest:(...args)=>window.nativeLan(...args)};});
  const a=await pc.newPage(),b=await phone.newPage();a.on('pageerror',e=>errors.push(e.message));b.on('pageerror',e=>errors.push(e.message));
  await a.goto('http://127.0.0.1:4323');await b.goto('http://127.0.0.1:4323');
  await a.locator('#new-note').click();await a.locator('#new-dialog [data-create="text"]').click();await a.locator('#title').fill('Cours sans compte');await a.locator('#text').fill('Bonjour depuis mon PC');await a.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  await a.locator('#local-sync').click();await a.locator('#show-code').click();await a.waitForFunction(()=>document.querySelector('#pair-code').value.startsWith('ECHO-'));const code=await a.locator('#pair-code').inputValue();assert.ok(await a.locator('#pair-qr').isVisible());
  await b.locator('#local-sync').click();await b.locator('#pair-input').fill(code);await b.locator('#pair-submit').click();await b.waitForFunction(()=>document.querySelector('#lan-status').textContent.includes('Association réussie'));
  await b.locator('#close-lan').click();await b.getByRole('button',{name:/Cours sans compte/}).click();assert.equal(await b.locator('#text').inputValue(),'Bonjour depuis mon PC');await b.locator('#text').fill('Réponse du téléphone');await b.waitForFunction(()=>document.querySelector('#save-status').textContent==='Enregistré sur cet appareil');
  await b.evaluate(async()=>{await(await import('./sync.mjs')).sync();});await a.locator('#lan-sync-now').click();await a.waitForFunction(()=>document.querySelector('#text').value==='Réponse du téléphone');
  await a.locator('#close-lan').click();await a.locator('#appearance').click();await a.locator('#theme-mode').selectOption('light');await a.locator('[data-color="#7EC8FF"]').click();assert.equal(await a.evaluate(()=>document.documentElement.style.colorScheme),'light');await a.locator('#close-theme').click();
  mkdirSync('test-results',{recursive:true});await a.waitForTimeout(250);await a.screenshot({path:'test-results/local-light.png',fullPage:true,animations:'disabled'});await a.locator('#appearance').click();await a.locator('#theme-mode').selectOption('dark');await a.locator('[data-color="#E879F9"]').click();await a.locator('#close-theme').click();await a.screenshot({path:'test-results/local-dark.png',fullPage:true,animations:'disabled'});
  await b.reload();await b.getByRole('button',{name:/Cours sans compte/}).click();assert.equal(await b.locator('#text').inputValue(),'Réponse du téléphone');await b.screenshot({path:'test-results/local-phone.png',fullPage:true});
  assert.deepEqual(errors,[]);
 }finally{await browser.close();await new Promise(ok=>app.server.close(ok));assert.ok(dir.startsWith(tmpdir()));rmSync(dir,{recursive:true,force:true});}
});
